// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2023.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
// ******************************************************************************
package ffx.algorithms.dynamics;

import static ffx.utilities.Constants.FSEC_TO_PSEC;
import static ffx.utilities.Constants.KCAL_TO_GRAM_ANG2_PER_PS2;
import static ffx.utilities.Constants.NS2SEC;
import static java.lang.String.format;
import static java.lang.System.arraycopy;
import static java.util.Arrays.fill;

import edu.rit.pj.Comm;
import ffx.algorithms.AlgorithmListener;
import ffx.algorithms.Terminatable;
import ffx.algorithms.dynamics.integrators.BetterBeeman;
import ffx.algorithms.dynamics.integrators.Integrator;
import ffx.algorithms.dynamics.integrators.IntegratorEnum;
import ffx.algorithms.dynamics.integrators.Respa;
import ffx.algorithms.dynamics.integrators.Stochastic;
import ffx.algorithms.dynamics.integrators.VelocityVerlet;
import ffx.algorithms.dynamics.thermostats.Adiabatic;
import ffx.algorithms.dynamics.thermostats.Berendsen;
import ffx.algorithms.dynamics.thermostats.Bussi;
import ffx.algorithms.dynamics.thermostats.Thermostat;
import ffx.algorithms.dynamics.thermostats.ThermostatEnum;
import ffx.algorithms.mc.MonteCarloListener;
import ffx.crystal.Crystal;
import ffx.numerics.Constraint;
import ffx.numerics.Potential;
import ffx.numerics.math.RunningStatistics;
import ffx.potential.ForceFieldEnergy;
import ffx.potential.ForceFieldEnergyOpenMM;
import ffx.potential.MolecularAssembly;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.LambdaInterface;
import ffx.potential.extended.ExtendedSystem;
import ffx.potential.parsers.DYNFilter;
import ffx.potential.parsers.PDBFilter;
import ffx.potential.parsers.SystemFilter;
import ffx.potential.parsers.XYZFilter;
import ffx.potential.parsers.XPHFilter;
import ffx.potential.utils.EnergyException;
import ffx.potential.utils.PotentialsFunctions;
import ffx.potential.utils.PotentialsUtils;
import ffx.utilities.FileUtils;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.io.FilenameUtils;

/**
 * Run NVE, NVT, or NPT molecular dynamics.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
public class MolecularDynamics implements Runnable, Terminatable {

  private static final Logger logger = Logger.getLogger(MolecularDynamics.class.getName());
  private static final double DEFAULT_RESTART_INTERVAL = 1.0;
  private static final double DEFAULT_LOG_INTERVAL = 0.25;
  private static final double DEFAULT_TRAJECTORY_INTERVAL = 10.0;
  /** By default, wait 25000 ns (25 us) in between polling the dynamics thread. */
  private static final int DEFAULT_DYNAMICS_SLEEP_TIME = 25000;
  /** MolecularAssembly to run dynamics on. */
  protected final MolecularAssembly molecularAssembly;
  /** List of MolecularAssembly instances. */
  protected final List<AssemblyInfo> assemblies;
  /** Propogate dynamics on this potential surface. */
  private final Potential potential;
  /** Verbose logging of dynamics state. */
  private final boolean verboseDynamicsState;
  /** Wait this many nanoseconds in between polling the dynamics thread. */
  private final int dynSleepTime;
  /** An Algorithm Listener to send updates to the GUI. */
  protected final AlgorithmListener algorithmListener;
  /** Total simulation time. */
  protected double totalSimTime = 0.0;
  /** Flag to indicate a run has finished. */
  protected boolean done;
  /** Flag to indicate velocities should be initialized. */
  protected boolean initVelocities = true;
  /** Coordinates. */
  protected double[] x;
  /** Velocities. */
  protected double[] v;
  /** Accelerations. */
  protected double[] a;
  /** Previous accelerations. */
  protected double[] aPrevious;
  /** The gradient. */
  protected double[] gradient;
  /** Mass for each degree of freedom. */
  protected double[] mass;
  /** Time step (picoseconds). Always use setTimestep to set! */
  protected double dt = 0.001;
  /**
   * Whether MD handles writing restart/trajectory files itself (true), or will be commanded by
   * another class (false) to do it. The latter is true for MC-OST, for example.
   */
  protected boolean automaticWriteouts = true;
  /** Time between writing out restart/checkpoint files in picoseconds. */
  protected double restartInterval = DEFAULT_RESTART_INTERVAL;
  /** Timesteps between writing out restart/checkpoint files. Set by the init method. */
  protected int restartFrequency;
  /** Timesteps between adding a frame to the trajectory file. Set by the init method. */
  protected int trajectoryFrequency;
  /** TImesteps between logging information to the screen. Set by the init method. */
  protected int logFrequency;
  /** Temperature of the system as of the last init call (and start of the last dynamics run). */
  protected double initialTemp;
  /** Kinetic energy of the system as of the last init call (and start of the last dynamics run). */
  protected double initialKinetic;
  /**
   * Potential energy of the system as of the last init call (and start of the last dynamics run).
   */
  protected double initialPotential;
  /** Total energy of the system as of the last init call (and start of the last dynamics run). */
  protected double initialTotal;
  /** File type to use when saving files. */
  protected String fileType = "XYZ";
  /** Flag to indicate use of constant pressure. */
  boolean constantPressure = false;
  /** Any Barostat that may be in use. */
  Barostat barostat = null;
  /** Log basic information at this level. Always at or above intermediateLogging. */
  Level basicLogging = Level.INFO;
  /** Dynamics restart file. */
  File restartFile = null;
  /** Flag to indicate loading of restart file. */
  boolean loadRestart = false;
  /** Filter to parse the dynamics restart file. */
  DYNFilter dynFilter = null;
  /** Flag to indicate dynamics has been initialized. */
  boolean initialized = false;
  /** Number of dynamics variables. */
  int numberOfVariables;
  /** Target temperature. ToDo: use the Thermostat instance. */
  double targetTemperature = 298.15;
  /** Current temperature. */
  double currentTemperature;
  /** Current kinetic energy. */
  double currentKineticEnergy;
  /** Integrator instance. */
  private final Integrator integrator;
  /** Keep some old coordinate snapshots around. */
  private final int numSnapshotsToKeep;
  /** Circular FIFO queues will simply discard old elements. */
  private final CircularFifoQueue<CoordinateSnapshot> lastSnapshots;
  /** Current potential energy. */
  double currentPotentialEnergy;
  /** Current total energy. */
  double currentTotalEnergy;
  /** Save snapshots in PDB format. */

  private RunningStatistics temperatureStats = new RunningStatistics();
  private RunningStatistics potentialEnergyStats = new RunningStatistics();
  private RunningStatistics kineticEnergyStats = new RunningStatistics();
  private RunningStatistics totalEnergyStats = new RunningStatistics();

  boolean saveSnapshotAsPDB = true;
  /** Number of MD steps to take. */
  private long nSteps = 1000;
  /** State of the dynamics. */
  private DynamicsState dynamicsState;
  /** Indicates how verbose MD should be. */
  private VerbosityLevel verbosityLevel = VerbosityLevel.VERBOSE;
  /** Time between appending to the trajectory file in picoseconds. */
  private double trajectoryInterval = DEFAULT_TRAJECTORY_INTERVAL;
  /** Thermostat instance. */
  private Thermostat thermostat;
  /** Flag to indicate MD should be terminated. */
  private boolean terminate = false;
  /** Time between logging information to the screen in picoseconds. */
  private double logInterval = DEFAULT_LOG_INTERVAL;
  /** ESV System. */
  private ExtendedSystem esvSystem;
  /** ESV Stochastic integrator. */
  private Stochastic esvIntegrator;
  /** ESV Adiabatic thermostat. */
  private Adiabatic esvThermostat;
  /** Frequency to print ESV info. */
  private int printEsvFrequency = -1;
  /** If asked to perform dynamics with a null dynamics file, write here. */
  private File fallbackDynFile;

  /**
   * Constructor for MolecularDynamics.
   *
   * @param assembly a {@link ffx.potential.MolecularAssembly} object.
   * @param potentialEnergy a {@link ffx.numerics.Potential} object.
   * @param properties a {@link org.apache.commons.configuration2.CompositeConfiguration}
   *     object.
   * @param listener a {@link ffx.algorithms.AlgorithmListener} object.
   * @param requestedThermostat a {@link ThermostatEnum} object.
   * @param requestedIntegrator a {@link ffx.algorithms.dynamics.integrators.IntegratorEnum}
   *     object.
   */
  public MolecularDynamics(
      MolecularAssembly assembly,
      Potential potentialEnergy,
      CompositeConfiguration properties,
      AlgorithmListener listener,
      ThermostatEnum requestedThermostat,
      IntegratorEnum requestedIntegrator) {
    this(
        assembly,
        potentialEnergy,
        properties,
        listener,
        requestedThermostat,
        requestedIntegrator,
        defaultFallbackDyn(assembly));
  }

  /**
   * Constructor for MolecularDynamics.
   *
   * @param assembly a {@link ffx.potential.MolecularAssembly} object.
   * @param potentialEnergy a {@link ffx.numerics.Potential} object.
   * @param properties a {@link org.apache.commons.configuration2.CompositeConfiguration}
   *     object.
   * @param listener a {@link ffx.algorithms.AlgorithmListener} object.
   * @param requestedThermostat a {@link ThermostatEnum} object.
   * @param requestedIntegrator a {@link ffx.algorithms.dynamics.integrators.IntegratorEnum}
   *     object.
   * @param fallbackDyn File to write restarts to if none is provided by the dynamics method.
   */
  public MolecularDynamics(
      MolecularAssembly assembly,
      Potential potentialEnergy,
      CompositeConfiguration properties,
      AlgorithmListener listener,
      ThermostatEnum requestedThermostat,
      IntegratorEnum requestedIntegrator,
      File fallbackDyn) {
    this.molecularAssembly = assembly;
    assemblies = new ArrayList<>();
    assemblies.add(new AssemblyInfo(assembly));
    this.algorithmListener = listener;
    this.potential = potentialEnergy;
    if (potentialEnergy instanceof Barostat) {
      constantPressure = true;
      barostat = (Barostat) potentialEnergy;
    }

    dynSleepTime = properties.getInt("dynamics-sleep-nanos", DEFAULT_DYNAMICS_SLEEP_TIME);

    assemblies.get(0).compositeConfiguration = properties;
    mass = potentialEnergy.getMass();
    numberOfVariables = potentialEnergy.getNumberOfVariables();
    x = new double[numberOfVariables];
    v = new double[numberOfVariables];
    a = new double[numberOfVariables];
    aPrevious = new double[numberOfVariables];
    gradient = new double[numberOfVariables];

    // If an Integrator wasn't passed to the MD constructor, check for one specified as a property.
    if (requestedIntegrator == null) {
      String integrate = properties.getString("integrate", "verlet").trim();
      try {
        requestedIntegrator = IntegratorEnum.valueOf(integrate);
      } catch (Exception e) {
        requestedIntegrator = IntegratorEnum.VERLET;
      }
    }
    boolean oMMLogging = false;
    if (potential instanceof ffx.potential.ForceFieldEnergyOpenMM) {
      oMMLogging = true;
    }

    List<Constraint> constraints = potentialEnergy.getConstraints();

    switch (requestedIntegrator) {
      case RESPA:
        Respa respa = new Respa(numberOfVariables, x, v, a, aPrevious, mass);
        int in = molecularAssembly.getProperties().getInt("respa-dt", 4);
        if (in < 2) {
          in = 2;
        }
        if (!oMMLogging) {
          respa.setInnerTimeSteps(in);
        }
        logger.log(Level.FINE, format(" Created a RESPA integrator with %d inner time steps.", in));
        integrator = respa;
        break;
      case STOCHASTIC:
        double friction = properties.getDouble("friction", 91.0);
        logger.log(Level.FINE, format(" Friction set at %.3f collisions/picosecond", friction));

        Stochastic stochastic = new Stochastic(friction, numberOfVariables, x, v, a, mass);
        if (properties.containsKey("randomseed")) {
          stochastic.setRandomSeed(properties.getInt("randomseed", 0));
        }
        integrator = stochastic;
        // The stochastic dynamics integration procedure will thermostat
        // the system. The ADIABTIC thermostat just serves to report the
        // temperature and initialize velocities if necessary.
        requestedThermostat = ThermostatEnum.ADIABATIC;
        break;
      case BEEMAN:
        integrator = new BetterBeeman(numberOfVariables, x, v, a, aPrevious, mass);
        break;
      case VERLET:
      case VELOCITYVERLET:
      default:
        integrator = new VelocityVerlet(numberOfVariables, x, v, a, mass);
    }

    integrator.addConstraints(constraints);

    // If a Thermostat wasn't passed to the MD constructor, check for one specified as a property.
    if (requestedThermostat == null) {
      String thermo = properties.getString("thermostat", "Berendsen").trim();
      try {
        requestedThermostat = ThermostatEnum.valueOf(thermo);
      } catch (Exception e) {
        requestedThermostat = ThermostatEnum.BERENDSEN;
      }
    }

    switch (requestedThermostat) {
      case BERENDSEN:
        double tau = properties.getDouble("tau-temperature", 0.2);
        thermostat =
            new Berendsen(numberOfVariables, x, v, mass,
                potentialEnergy.getVariableTypes(), targetTemperature,
                tau, constraints);
        break;
      case BUSSI:
        tau = properties.getDouble("tau-temperature", 0.2);
        thermostat = new Bussi(numberOfVariables, x, v, mass,
                potentialEnergy.getVariableTypes(), targetTemperature,
                tau, constraints);
        if (properties.containsKey("randomseed")) {
          thermostat.setRandomSeed(properties.getInt("randomseed", 0));
        }
        break;
      case ADIABATIC:
      default:
        thermostat = new Adiabatic(numberOfVariables, x, v, mass,
            potentialEnergy.getVariableTypes(), constraints);
    }

    if (properties.containsKey("randomseed")) {
      thermostat.setRandomSeed(properties.getInt("randomseed", 0));
    }

    // For Stochastic dynamics, center of mass motion will not be removed.
    if (integrator instanceof Stochastic) {
      thermostat.setRemoveCenterOfMassMotion(false);
    }

    numSnapshotsToKeep = properties.getInteger("dynamicsSnapshotMemory", 0);
    // Cannot construct a CircularFifoQueue of zero length.
    lastSnapshots = new CircularFifoQueue<>(Math.max(numSnapshotsToKeep, 1));

    verboseDynamicsState = properties.getBoolean("md-verbose", false);
    done = true;

    fallbackDynFile = fallbackDyn;
  }

  /**
   * Constructor for MolecularDynamics.
   *
   * @param assembly a {@link ffx.potential.MolecularAssembly} object.
   * @param potentialEnergy a {@link ffx.numerics.Potential} object.
   * @param properties a {@link org.apache.commons.configuration2.CompositeConfiguration}
   *     object.
   * @param listener a {@link ffx.algorithms.AlgorithmListener} object.
   * @param requestedThermostat a {@link ThermostatEnum} object.
   * @param requestedIntegrator a {@link ffx.algorithms.dynamics.integrators.IntegratorEnum}
   *     object.
   * @param esvSystem a {@link ffx.potential.extended.ExtendedSystem} object.
   */
  public MolecularDynamics(
      MolecularAssembly assembly,
      Potential potentialEnergy,
      CompositeConfiguration properties,
      AlgorithmListener listener,
      ThermostatEnum requestedThermostat,
      IntegratorEnum requestedIntegrator,
      ExtendedSystem esvSystem) {
    this(assembly, potentialEnergy, properties, listener, requestedThermostat, requestedIntegrator);
    this.esvSystem = esvSystem;
  }

  /**
   * Method that determines whether a dynamics is done by the java implementation native to ffx or
   * the OpenMM implementation
   *
   * @param assembly a {@link ffx.potential.MolecularAssembly} object.
   * @param potentialEnergy a {@link ffx.numerics.Potential} object.
   * @param properties a {@link org.apache.commons.configuration2.CompositeConfiguration}
   *     object.
   * @param listener a {@link ffx.algorithms.AlgorithmListener} object.
   * @param requestedThermostat a {@link ThermostatEnum} object.
   * @param requestedIntegrator a {@link ffx.algorithms.dynamics.integrators.IntegratorEnum}
   *     object.
   * @return a {@link MolecularDynamics} object.
   */
  public static MolecularDynamics dynamicsFactory(
      MolecularAssembly assembly,
      Potential potentialEnergy,
      CompositeConfiguration properties,
      AlgorithmListener listener,
      ThermostatEnum requestedThermostat,
      IntegratorEnum requestedIntegrator) {

    return dynamicsFactory(
        assembly,
        potentialEnergy,
        properties,
        listener,
        requestedThermostat,
        requestedIntegrator,
        defaultEngine(assembly, potentialEnergy));
  }

  /**
   * dynamicsFactory.
   *
   * @param assembly a {@link ffx.potential.MolecularAssembly} object.
   * @param potentialEnergy a {@link ffx.numerics.Potential} object.
   * @param properties a {@link org.apache.commons.configuration2.CompositeConfiguration}
   *     object.
   * @param listener a {@link ffx.algorithms.AlgorithmListener} object.
   * @param requestedThermostat a {@link ThermostatEnum} object.
   * @param requestedIntegrator a {@link ffx.algorithms.dynamics.integrators.IntegratorEnum}
   *     object.
   * @param engine a {@link MolecularDynamics.DynamicsEngine} object.
   * @return a {@link MolecularDynamics} object.
   */
  public static MolecularDynamics dynamicsFactory(
      MolecularAssembly assembly,
      Potential potentialEnergy,
      CompositeConfiguration properties,
      AlgorithmListener listener,
      ThermostatEnum requestedThermostat,
      IntegratorEnum requestedIntegrator,
      DynamicsEngine engine) {
    switch (engine) {
      case OPENMM:
      case OMM:
        // TODO: Replace this with calls to the leaves of a proper tree structure.
        // Unfortunately, neither Java, nor Apache Commons, nor Guava has an arbitrary tree
        // implementing Collection.
        // Nor does javax.swing have a quick "get me the leaves" method that I was able to find.
        boolean ommLeaves =
            potentialEnergy.getUnderlyingPotentials().stream()
                .anyMatch((Potential p) -> p instanceof ForceFieldEnergyOpenMM);
        ommLeaves = ommLeaves || potentialEnergy instanceof ForceFieldEnergyOpenMM;
        if (ommLeaves) {
          return new MolecularDynamicsOpenMM(
              assembly,
              potentialEnergy,
              properties,
              listener,
              requestedThermostat,
              requestedIntegrator);
        } else {
          throw new IllegalArgumentException(
              format(
                  " Requested OpenMM engine %s, but at least one leaf of the potential %s is not an OpenMM force field!",
                  engine, potentialEnergy));
        }
      case FFX:
      default:
        return new MolecularDynamics(
            assembly,
            potentialEnergy,
            properties,
            listener,
            requestedThermostat,
            requestedIntegrator);
    }
  }

  private static DynamicsEngine defaultEngine(
      MolecularAssembly molecularAssembly, Potential potentialEnergy) {
    CompositeConfiguration properties = molecularAssembly.getProperties();
    String mdEngine = properties.getString("MD-engine");
    if (mdEngine != null) {
      if (mdEngine.equalsIgnoreCase("OMM")) {
        logger.info(" Creating OpenMM Dynamics Object");
        return DynamicsEngine.OPENMM;
      } else {
        logger.info(" Creating FFX Dynamics Object");
        return DynamicsEngine.FFX;
      }
    } else {
      // TODO: Replace this with a better check.
      boolean ommLeaves =
          potentialEnergy.getUnderlyingPotentials().stream()
              .anyMatch((Potential p) -> p instanceof ForceFieldEnergyOpenMM);
      ommLeaves = ommLeaves || potentialEnergy instanceof ForceFieldEnergyOpenMM;
      if (ommLeaves) {
        return DynamicsEngine.OPENMM;
      } else {
        return DynamicsEngine.FFX;
      }
    }
  }

  /**
   * Creates the "default" fallback dynamics file object (does not create actual file!)
   *
   * @param assembly First assembly.
   * @return Default fallback file.
   */
  private static File defaultFallbackDyn(MolecularAssembly assembly) {
    String firstFileName = FilenameUtils.removeExtension(assembly.getFile().getAbsolutePath());
    return new File(firstFileName + ".dyn");
  }

  /**
   * Adds a MolecularAssembly to be tracked by this MolecularDynamics. Note: does not affect the
   * underlying Potential.
   *
   * @param mola A MolecularAssembly to be tracked
   * @param props Associated CompositeConfiguration
   */
  public void addAssembly(MolecularAssembly mola, CompositeConfiguration props) {
    AssemblyInfo mi = new AssemblyInfo(mola);
    mi.compositeConfiguration = props;
    assemblies.add(mi);
  }

  /**
   * attachExtendedSystem.
   *
   * @param system a {@link ffx.potential.extended.ExtendedSystem} object.
   */
  public void attachExtendedSystem(ExtendedSystem system, double reportFreq) {
    if (esvSystem != null) {
      logger.warning("An ExtendedSystem is already attached to this MD!");
    }
    esvSystem = system;
    this.esvIntegrator = new Stochastic(esvSystem.getThetaFriction(),
        esvSystem.getNumberOfVariables(), esvSystem.getThetaPosition(),
        esvSystem.getThetaVelocity(), esvSystem.getThetaAccel(), esvSystem.getThetaMassArray());
    this.esvThermostat = new Adiabatic(esvSystem.getNumberOfVariables(),
        esvSystem.getThetaPosition(),
        esvSystem.getThetaVelocity(), esvSystem.getThetaMassArray(), potential.getVariableTypes());
    printEsvFrequency = intervalToFreq(reportFreq, "Reporting (logging) interval");
    logger.info(
        format("  Attached extended system (%s) to molecular dynamics.", esvSystem.toString()));
    logger.info(format("  Extended System Theta Friction: %f", esvSystem.getThetaFriction()));
    logger.info(format("  Extended System Theta Mass: %f", esvSystem.getThetaMass()));
    logger.info(format("  Extended System Lambda Print Frequency: %d (fsec)", printEsvFrequency));
    reInit();
  }

  /**
   * Blocking molecular dynamics. When this method returns, the MD run is done.
   *
   * @param nSteps Number of MD steps
   * @param timeStep Time step in femtoseconds
   * @param loggingInterval Interval between printing/logging information in picoseconds.
   * @param trajectoryInterval Interval between adding a frame to the trajectory file in
   *     picoseconds.
   * @param temperature Temperature in Kelvins.
   * @param initVelocities Initialize new velocities from a Maxwell-Boltzmann distribution.
   * @param fileType XYZ or ARC to save to .arc, PDB for .pdb files
   * @param restartInterval Interval between writing new restart files in picoseconds.
   * @param dyn A {@link java.io.File} object to write the restart file to.
   */
  public void dynamic(final long nSteps, final double timeStep,
      final double loggingInterval, final double trajectoryInterval,
      final double temperature, final boolean initVelocities,
      String fileType, double restartInterval, final File dyn) {
    this.fileType = fileType;
    setRestartFrequency(restartInterval);
    dynamic(nSteps, timeStep, loggingInterval, trajectoryInterval, temperature, initVelocities, dyn);
  }

  /**
   * Blocking molecular dynamics. When this method returns, the MD run is done.
   *
   * @param nSteps Number of MD steps
   * @param timeStep Time step in femtoseconds
   * @param loggingInterval Interval between printing/logging information in picoseconds.
   * @param trajectoryInterval Interval between adding a frame to the trajectory file in
   *     picoseconds.
   * @param temperature Temperature in Kelvins.
   * @param initVelocities Initialize new velocities from a Maxwell-Boltzmann distribution.
   * @param dyn A {@link java.io.File} object to write the restart file to.
   */
  public void dynamic(final long nSteps, final double timeStep,
      final double loggingInterval, final double trajectoryInterval,
      final double temperature, final boolean initVelocities, final File dyn) {
    // Return if already running;
    // Could happen if two threads call dynamic on the same MolecularDynamics instance.
    if (!done) {
      logger.warning(" Programming error - a thread invoked dynamic when it was already running.");
      return;
    }

    init(nSteps, timeStep, loggingInterval, trajectoryInterval, fileType, restartInterval,
        temperature, initVelocities, dyn);

    Thread dynamicThread = new Thread(this);
    dynamicThread.start();
    synchronized (this) {
      try {
        while (dynamicThread.isAlive()) {
          wait(0, dynSleepTime);
        }
      } catch (InterruptedException e) {
        String message = " Molecular dynamics interrupted.";
        logger.log(Level.WARNING, message, e);
      }
    }
    if (!verbosityLevel.isQuiet) {
      logger.info(" Done with an MD round.");
    }
  }

  public MolecularAssembly[] getAssemblies() {
    return assemblies.stream().map(AssemblyInfo::getAssembly).toArray(MolecularAssembly[]::new);
  }

  /**
   * Returns the associated dynamics file.
   *
   * @return Dynamics restart File.
   */
  public File getDynFile() {
    return restartFile;
  }

  /**
   * Gets the kinetic energy at the start of the last dynamics run.
   *
   * @return Kinetic energy at the start of the run.
   */
  public double getInitialKineticEnergy() {
    return initialKinetic;
  }

  /**
   * Gets the potential energy at the start of the last dynamics run.
   *
   * @return potential energy at the start of the run.
   */
  public double getInitialPotentialEnergy() {
    return initialPotential;
  }

  /**
   * Gets the temperature at the start of the last dynamics run.
   *
   * @return temperature at the start of the run.
   */
  public double getInitialTemperature() {
    return initialTemp;
  }

  /**
   * Gets the total energy at the start of the last dynamics run.
   *
   * @return total energy at the start of the run.
   */
  public double getInitialTotalEnergy() {
    return initialTotal;
  }

  /**
   * getIntervalSteps.
   *
   * @return Always 1 for this implementation.
   */
  public int getIntervalSteps() {
    return 1;
  }

  /**
   * No-op; FFX does not need to occasionally return information from FFX.
   *
   * @param intervalSteps Ignored.
   */
  public void setIntervalSteps(int intervalSteps) {
    // Not meaningful for FFX MD.
  }

  /**
   * Get the system kinetic energy.
   *
   * @return kinetic energy.
   */
  public double getKineticEnergy() {
    return currentKineticEnergy;
  }

  /**
   * Get the system potential energy.
   *
   * @return potential energy.
   */
  public double getPotentialEnergy() {
    return currentPotentialEnergy;
  }

  /**
   * Get the current temperature of the system
   *
   * @return currentTemperature
   */
  public double getTemperature() {
    return currentTemperature;
  }

  /**
   * Getter for the field <code>thermostat</code>.
   *
   * @return a {@link ffx.algorithms.dynamics.thermostats.Thermostat} object.
   */
  public Thermostat getThermostat() {
    return thermostat;
  }

  /**
   * Setter for the field <code>thermostat</code>.
   *
   * @param thermostat a {@link ffx.algorithms.dynamics.thermostats.Thermostat} object.
   */
  public void setThermostat(Thermostat thermostat) {
    this.thermostat = thermostat;
  }

  /**
   * getTimeStep.
   *
   * @return Timestep in picoseconds.
   */
  public double getTimeStep() {
    return dt;
  }

  /**
   * Sets the timestep and resets frequencies based on stored intervals.
   *
   * @param step Timestep in femtoseconds.
   */
  private void setTimeStep(double step) {
    dt = step * FSEC_TO_PSEC;
    // Reset frequencies to be consistent with new timestep.
    setRestartFrequency(restartInterval);
    setLoggingFrequency(logInterval);
    setTrajectoryFrequency(trajectoryInterval);
  }

  /**
   * Get the total system energy (kinetic plus potential).
   *
   * @return total energy.
   */
  public double getTotalEnergy() {
    return currentTotalEnergy;
  }

  public VerbosityLevel getVerbosityLevel() {
    return verbosityLevel;
  }

  public void setVerbosityLevel(VerbosityLevel level) {
    verbosityLevel = level;
    switch (level) {
      case SILENT:
        basicLogging = Level.FINE;
        break;
      case QUIET:
        basicLogging = Level.INFO;
        break;
      case VERBOSE:
      default:
        basicLogging = Level.INFO;
    }
  }

  /**
   * init
   *
   * @param nSteps Number of MD steps
   * @param timeStep Time step in femtoseconds
   * @param loggingInterval Interval between printing/logging information in picoseconds.
   * @param trajectoryInterval Interval between adding a frame to the trajectory file in
   *     picoseconds.
   * @param fileType XYZ or ARC to save to .arc, PDB for .pdb files
   * @param restartInterval Interval between writing new restart files in picoseconds.
   * @param temperature Temperature in Kelvins.
   * @param initVelocities Initialize new velocities from a Maxwell-Boltzmann distribution.
   * @param dyn A {@link java.io.File} object to write the restart file to.
   */
  public void init(
      final long nSteps,
      final double timeStep,
      final double loggingInterval,
      final double trajectoryInterval,
      final String fileType,
      final double restartInterval,
      final double temperature,
      final boolean initVelocities,
      final File dyn) {

    // Return if already running.
    if (!done) {
      logger.warning(
          " Programming error - attempt to modify parameters of a running MolecularDynamics instance.");
      return;
    }

    if (integrator instanceof Stochastic) {
      if (constantPressure) {
        logger.log(basicLogging, "\n Stochastic dynamics in the NPT ensemble");
      } else {
        logger.log(basicLogging, "\n Stochastic dynamics in the NVT ensemble");
      }
    } else if (!(thermostat instanceof Adiabatic)) {
      if (constantPressure) {
        logger.log(basicLogging, "\n Molecular dynamics in the NPT ensemble");
      } else {
        logger.log(basicLogging, "\n Molecular dynamics in the NVT ensemble");
      }
    } else {
      if (constantPressure) {
        logger.severe("\n NPT Molecular dynamics requires a thermostat");
      } else {
        logger.log(basicLogging, "\n Molecular dynamics in the NVE ensemble");
      }
    }

    this.nSteps = nSteps;
    totalSimTime = 0.0;

    // Convert the time step from femtoseconds to picoseconds.
    setTimeStep(timeStep);

    // Convert intervals in ps to frequencies in timesteps.
    setLoggingFrequency(loggingInterval);
    setTrajectoryFrequency(trajectoryInterval);
    setRestartFrequency(restartInterval);

    sanityCheckFrequency("Reporting           ", logFrequency, this::setLoggingFrequency);
    if (automaticWriteouts) {
      // Only sanity check these values if MD is doing this itself.
      sanityCheckFrequency("Trajectory Write-Out", trajectoryFrequency,
          this::setTrajectoryFrequency);
      sanityCheckFrequency("Restart             ", restartFrequency, this::setRestartFrequency);
    }

    // Set snapshot file type.
    saveSnapshotAsPDB = true;
    if (fileType.equalsIgnoreCase("XYZ") || fileType.equalsIgnoreCase("ARC")) {
      saveSnapshotAsPDB = false;
    } else if (!fileType.equalsIgnoreCase("PDB")) {
      logger.warning("Snapshot file type unrecognized; saving snapshots as PDB.\n");
    }

    assemblyInfo();

    this.restartFile = (dyn == null) ? fallbackDynFile : dyn;
    loadRestart = restartFile.exists() && !initialized;

    if (dynFilter == null) {
      dynFilter = new DYNFilter(molecularAssembly.getName());
    }

    this.targetTemperature = temperature;
    this.initVelocities = initVelocities;
    done = false;

    if (loadRestart) {
      logger.info("  Continuing from " + restartFile.getAbsolutePath());
    }

    if (!verbosityLevel.isQuiet) {
      logger.info(format("  Integrator:          %15s", integrator.toString()));
      logger.info(format("  Thermostat:          %15s", thermostat.toString()));
      logger.info(format("  Number of steps:     %8d", nSteps));
      logger.info(format("  Time step:           %8.3f (fsec)", timeStep));
      logger.info(format("  Print interval:      %8.3f (psec)", loggingInterval));
      logger.info(format("  Save interval:       %8.3f (psec)", trajectoryInterval));
      if (assemblies.size() > 1) {
        for (int i = 0; i < assemblies.size(); i++) {
          AssemblyInfo ai = assemblies.get(i);
          logger.info(format("  Archive file %3d: %s", (i + 1), ai.archiveFile.getName()));
        }
      } else {
        logger.info(format("  Archive file:     %s", assemblies.get(0).archiveFile.getAbsolutePath()));
      }
      logger.info(format("  Restart file:     %s", restartFile.getAbsolutePath()));
    }
  }

  /**
   * A version of init with the original method header. Redirects to the new method with default
   * values for added parameters. Needed by (at least) ReplicaExchange, which calls this directly.
   *
   * @param nSteps Number of MD steps
   * @param timeStep Time step in femtoseconds
   * @param loggingInterval Interval between printing/logging information in picoseconds.
   * @param trajectoryInterval Interval between adding a frame to the trajectory file in
   *     picoseconds.
   * @param temperature Temperature in Kelvins.
   * @param initVelocities Initialize new velocities from a Maxwell-Boltzmann distribution.
   * @param dyn A {@link java.io.File} object to write the restart file to.
   */
  public void init(
      final long nSteps,
      final double timeStep,
      final double loggingInterval,
      final double trajectoryInterval,
      final double temperature,
      final boolean initVelocities,
      final File dyn) {
    init(
        nSteps,
        timeStep,
        loggingInterval,
        trajectoryInterval,
        "XYZ",
        restartInterval,
        temperature,
        initVelocities,
        dyn);
  }

  /** Reinitialize the MD engine after a chemical change. */
  public void reInit() {
    numberOfVariables = potential.getNumberOfVariables();
    mass = potential.getMass();
    x = new double[numberOfVariables];
    v = new double[numberOfVariables];
    a = new double[numberOfVariables];
    aPrevious = new double[numberOfVariables];
    gradient = new double[numberOfVariables];
    potential.getCoordinates(x);
    potential.getVelocity(v);
    potential.getAcceleration(a);
    potential.getPreviousAcceleration(aPrevious);
    if (potential instanceof ForceFieldEnergy) {
      gradient = ((ForceFieldEnergy) potential).getGradient(gradient);
    }
    thermostat.setNumberOfVariables(
        numberOfVariables, x, v, mass, potential.getVariableTypes(), true);
    integrator.setNumberOfVariables(numberOfVariables, x, v, a, aPrevious, mass);
  }

  /**
   * Causes this MolecularDynamics to take an additional set of timesteps.
   *
   * @param nSteps Number of steps to take
   * @param temperature Temperature of simulation
   */
  @Deprecated
  public void redynamic(final int nSteps, final double temperature) {
    if (!verbosityLevel.isQuiet()) {
      setVerbosityLevel(VerbosityLevel.QUIET);
    }

    this.nSteps = nSteps;
    totalSimTime = 0.0;
    targetTemperature = temperature;
    thermostat.setTargetTemperature(temperature);
    initVelocities = false;

    done = false;
    terminate = false;
    initialized = true;

    Thread dynamicThread = new Thread(this);
    dynamicThread.start();
    synchronized (this) {
      try {
        while (dynamicThread.isAlive()) {
          wait(100);
        }
      } catch (InterruptedException e) {
        String message = " Molecular dynamics interrupted.";
        logger.log(Level.WARNING, message, e);
      }
    }
  }

  /**
   * revertState.
   *
   * @throws java.lang.Exception if any.
   */
  public void revertState() throws Exception {
    if (dynamicsState == null) {
      throw new Exception();
    }
    dynamicsState.revertState();
  }

  /** {@inheritDoc} */
  @Override
  public void run() {
    try {
      preRunOps();
    } catch (IllegalStateException ise) {
      return;
    }
    initializeEnergies();
    postInitEnergies();
    mainLoop();
    postRun();
  }

  /**
   * Enable or disable automatic writeout of trajectory snapshots and restart files.
   *
   * <p>Primarily intended for use with MC-OST, which handles the timing itself.
   *
   * @param autoWriteout Whether to automatically write out trajectory and restart files.
   */
  public void setAutomaticWriteouts(boolean autoWriteout) {
    this.automaticWriteouts = autoWriteout;
  }

  /**
   * Sets the "fallback" .dyn file to write to if none is passed to the dynamic method.
   *
   * @param fallback Fallback dynamics restart file.
   */
  public void setFallbackDynFile(File fallback) {
    this.fallbackDynFile = fallback;
  }

  /**
   * Method to set file type from groovy scripts.
   *
   * @param fileType the type of snapshot files to write.
   */
  public void setFileType(String fileType) {
    this.fileType = fileType;
  }

  /**
   * Not meaningful for FFX dynamics (no need to obtain velocities/accelerations from a different
   * program, especially one running on a GPU). Is a no-op.
   *
   * @param obtainVA Not meaningful for this implementation.
   */
  public void setObtainVelAcc(boolean obtainVA) {
    // Not meaningful for FFX dynamics.
  }

  /**
   * Method to set the Restart Frequency.
   *
   * @param restartInterval Time in ps between writing restart files.
   * @throws java.lang.IllegalArgumentException If restart frequency is not a positive number
   */
  public void setRestartFrequency(double restartInterval) throws IllegalArgumentException {
    restartFrequency = intervalToFreq(restartInterval, "Restart interval");
    this.restartInterval = restartInterval;
  }

  public void setTrajectoryFiles(File[] outputFiles) {
    logger.info(" Setting files " + Arrays.toString(outputFiles));
    int nFi = assemblies.size();
    assert outputFiles.length == nFi;
    for (int i = 0; i < nFi; i++) {
      AssemblyInfo ai = assemblies.get(i);
      ai.setArchiveFile(outputFiles[i]);
    }
  }

  /** storeState. */
  public void storeState() {
    if (dynamicsState == null) {
      dynamicsState = new DynamicsState();
    }
    dynamicsState.storeState();
  }

  /** {@inheritDoc} */
  @Override
  public void terminate() {
    terminate = true;
    while (!done) {
      synchronized (this) {
        try {
          wait(1);
        } catch (Exception e) {
          logger.log(Level.WARNING, " Exception terminating dynamics.\n", e);
        }
      }
    }
  }

  /**
   * Write restart and trajectory files if the provided step matches the frequency and that file type
   * is requested.
   *
   * @param step Step to write files (if any) for.
   * @param trySnapshot If false, do not write snapshot even if the timestep is correct.
   * @param tryRestart If false, do not write a restart file even if the timestep is correct.
   * @return EnumSet of actions taken by this method.
   */
  public EnumSet<WriteActions> writeFilesForStep(
      long step, boolean trySnapshot, boolean tryRestart) {
    return writeFilesForStep(step, trySnapshot, tryRestart, null);
  }

  /**
   * Write restart and trajectory files if the provided step matches the frequency and that file type
   * is requested.
   *
   * @param step Step to write files (if any) for.
   * @param trySnapshot If false, do not write snapshot even if the timestep is correct.
   * @param tryRestart If false, do not write a restart file even if the timestep is correct.
   * @param extraLines Additional lines to append into the comments section of the snapshot (or
   *     null).
   * @return EnumSet of actions taken by this method.
   */
  public EnumSet<WriteActions> writeFilesForStep(
      long step, boolean trySnapshot, boolean tryRestart, String[] extraLines) {
    List<String> linesList =
        (extraLines == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(extraLines));

    if (potential instanceof LambdaInterface) {
      String lamString = format("Lambda: %.8f", ((LambdaInterface) potential).getLambda());
      linesList.add(lamString);
    }

    String tempString = format("Temp: %.2f", thermostat.getTargetTemperature());
    linesList.add(tempString);

    if (esvSystem != null) {
      String pHString = format("pH: %.2f", esvSystem.getConstantPh());
      linesList.add(pHString);
    }

    Comm world = Comm.world();
    if (world != null && world.size() > 1) {
      String rankString = format("Rank: %d", world.rank());
      linesList.add(rankString);
    }

    String[] allLines = new String[linesList.size()];
    allLines = linesList.toArray(allLines);

    EnumSet<WriteActions> written = EnumSet.noneOf(WriteActions.class);
    if (step != 0) {
      // Write out snapshots in selected format every saveSnapshotFrequency steps.
      if (trySnapshot && trajectoryFrequency > 0 && step % trajectoryFrequency == 0) {

        // Log stats.
        logger.info(format("\n Average Values for the Last %d Out of %d Dynamics Steps\n", trajectoryFrequency, step));
        logger.info(format("  Simulation Time  %16.4f Picosecond", step * dt));
        logger.info(format("  Total Energy     %16.4f Kcal/mole   (+/-%9.4f)",
            totalEnergyStats.getMean(), totalEnergyStats.getStandardDeviation()));
        logger.info(format("  Potential Energy %16.4f Kcal/mole   (+/-%9.4f)",
            potentialEnergyStats.getMean(), potentialEnergyStats.getStandardDeviation()));
        logger.info(format("  Kinetic Energy   %16.4f Kcal/mole   (+/-%9.4f)",
            kineticEnergyStats.getMean(), kineticEnergyStats.getStandardDeviation()));
        logger.info(format("  Temperature      %16.4f Kelvin      (+/-%9.4f)\n",
            temperatureStats.getMean(), temperatureStats.getStandardDeviation()));
        totalEnergyStats.reset();
        potentialEnergyStats.reset();
        kineticEnergyStats.reset();
        temperatureStats.reset();

        if (esvSystem != null) {
          for (Atom atom : molecularAssembly.getAtomList()) {
            int atomIndex = atom.getIndex() - 1;
            atom.setOccupancy(esvSystem.getTitrationLambda(atomIndex));
            atom.setTempFactor(esvSystem.getTautomerLambda(atomIndex));
          }
        }
        appendSnapshot(allLines);
        written.add(WriteActions.SNAPSHOT);
      }

      // Write out restart files every saveRestartFileFrequency steps.
      if (tryRestart && restartFrequency > 0 && step % restartFrequency == 0) {
        writeRestart();
        written.add(WriteActions.RESTART);
      }
    }
    return written;
  }

  /** Write out a restart file. */
  public void writeRestart() {
    potential.writeAdditionalRestartInfo(true);
    String dynName = FileUtils.relativePathTo(restartFile).toString();
    if (dynFilter.writeDYN(restartFile, molecularAssembly.getCrystal(), x, v, a, aPrevious)) {
      logger.log(basicLogging, " Wrote dynamics restart file to " + dynName);
    } else {
      logger.log(basicLogging, " Writing dynamics restart file to " + dynName + " failed");
    }
    if (esvSystem != null) {
      esvSystem.writeRestart();
      esvSystem.writeLambdaHistogram(false);
    }
  }

  /**
   * Performs the inner loop of writing snapshots to disk; used by both detectAtypicalEnergy and a
   * try-catch in dynamics.
   */
  public void writeStoredSnapshots() {
    int numSnaps = lastSnapshots.size();

    File origFile = molecularAssembly.getFile();
    String timeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH_mm_ss"));
    PotentialsFunctions potentialsFunctions = new PotentialsUtils();

    String filename =
        format(
            "%s-%s-SNAP.pdb",
            FilenameUtils.removeExtension(molecularAssembly.getFile().getName()), timeString);

    for (int is = 0; is < numSnaps; is++) {
      CoordinateSnapshot oldState = lastSnapshots.poll();
      if (oldState != null) {
        oldState.revertState();
      }
      potentialsFunctions.saveAsPDB(
          molecularAssembly, new File(potentialsFunctions.versionFile(filename)));
    }
    molecularAssembly.setFile(origFile);
  }

  /** assemblyInfo. */
  void assemblyInfo() {
    assemblies.forEach(
        (ainfo) -> {
          MolecularAssembly mola = ainfo.getAssembly();
          CompositeConfiguration aprops = ainfo.compositeConfiguration;
          File file = mola.getFile();
          String filename = FilenameUtils.removeExtension(file.getAbsolutePath());
          File archFile = ainfo.archiveFile;
          if (archFile == null) {
            archFile = new File(filename + ".arc");
            ainfo.archiveFile = XYZFilter.version(archFile);
          }
          if (ainfo.pdbFile == null) {
            String extName = FilenameUtils.getExtension(file.getName());
            if (extName.toLowerCase().startsWith("pdb")) {
              ainfo.pdbFile = file;
            } else {
              ainfo.pdbFile = new File(filename + ".pdb");
            }
          }
          if (ainfo.xyzFilter == null && esvSystem == null) {
            ainfo.xyzFilter = new XYZFilter(file, mola, mola.getForceField(), aprops);
          }
          if (ainfo.xphFilter == null) {
            ainfo.xphFilter = new XPHFilter(file, mola, mola.getForceField(), aprops, esvSystem);
          }
          if (ainfo.pdbFilter == null) {
            ainfo.pdbFilter = new PDBFilter(ainfo.pdbFile, mola, mola.getForceField(), aprops);
          }
        });
  }

  /**
   * Converts an interval in ps to a frequency in timesteps.
   *
   * @param interval Interval between events in ps
   * @param describe Description of the event.
   * @return Frequency of event in timesteps per event.
   * @throws IllegalArgumentException If interval is not a positive finite value.
   */
  private int intervalToFreq(double interval, String describe) throws IllegalArgumentException {
    if (!Double.isFinite(interval) || interval <= 0) {
      throw new IllegalArgumentException(
          format(" %s must be " + "positive finite value in ps, was %10.4g", describe, interval));
    }
    if (interval >= dt) {
      return (int) (interval / dt);
    } else {
      logger.warning(
          format(
              " Specified %s of %.6f ps < timestep %.6f ps; "
                  + "interval is set to once per timestep!",
              describe, interval, dt));
      return 1;
    }
  }

  /**
   * Method to set the logging/printing frequency.
   *
   * @param logInterval Time in ps between logging information to the screen.
   */
  private void setLoggingFrequency(double logInterval) {
    logFrequency = intervalToFreq(logInterval, "Reporting (logging) interval");
    this.logInterval = logInterval;
  }

  /**
   * Method to set the frequency of appending snapshots to the trajectory file.
   *
   * @param snapshotInterval Time in ps between appending snapshots to the trajectory file.
   */
  private void setTrajectoryFrequency(double snapshotInterval) {
    trajectoryFrequency = intervalToFreq(snapshotInterval, "Trajectory writeout interval");
    this.trajectoryInterval = snapshotInterval;
  }

  /** Performs basic pre-MD operations such as loading the restart file. */
  void preRunOps() throws IllegalStateException {
    done = false;
    terminate = false;

    // Set the target temperature.
    thermostat.setTargetTemperature(targetTemperature);
    boolean quiet = verbosityLevel.isQuiet();
    thermostat.setQuiet(quiet);
    if (integrator instanceof Stochastic) {
      Stochastic stochastic = (Stochastic) integrator;
      stochastic.setTemperature(targetTemperature);
    }

    // Set the step size.
    integrator.setTimeStep(dt);

    if (!initialized) {
      // Initialize from a restart file.
      if (loadRestart) {
        Crystal crystal = molecularAssembly.getCrystal();
        if (!dynFilter.readDYN(restartFile, crystal, x, v, a, aPrevious)) {
          String message = " Could not load the restart file - dynamics terminated.";
          logger.log(Level.WARNING, message);
          done = true;
          throw new IllegalStateException(message);
        } else {
          molecularAssembly.setCrystal(crystal);
        }
      } else {
        // Initialize using current atomic coordinates.
        potential.getCoordinates(x);
        // Initialize atomic velocities from a Maxwell-Boltzmann distribution or set to 0.
        if (initVelocities) {
          thermostat.maxwell(targetTemperature);
        } else {
          fill(v, 0.0);
        }
      }
    } else {
      // If MD has already been run (ie. Annealing or RepEx), then initialize velocities if
      // requested.
      if (initVelocities) {
        thermostat.maxwell(targetTemperature);
      }
    }
  }

  /** Initializes energy fields, esp. potential energy. */
  private void initializeEnergies() {
    // Compute the current potential energy.
    try {
      if (esvSystem != null && potential instanceof ForceFieldEnergyOpenMM) {
        currentPotentialEnergy = ((ForceFieldEnergyOpenMM) potential).energyAndGradientFFX(x,
            gradient);
      } else {
        currentPotentialEnergy = potential.energyAndGradient(x, gradient);
      }
    } catch (EnergyException ex) {
      writeStoredSnapshots();
      throw ex;
    }
    initialPotential = currentPotentialEnergy;

    // Initialize current and previous accelerations.
    if (!loadRestart || initialized || integrator instanceof Respa) {
      // For the Respa integrator, initial accelerations are from the slowly varying forces.
      if (integrator instanceof Respa) {
        potential.setEnergyTermState(Potential.STATE.SLOW);
        potential.energyAndGradient(x, gradient);
      }

      for (int i = 0; i < numberOfVariables; i++) {
        a[i] = -KCAL_TO_GRAM_ANG2_PER_PS2 * gradient[i] / mass[i];
      }

      if (aPrevious != null) {
        arraycopy(a, 0, aPrevious, 0, numberOfVariables);
      }
    }

    // Compute the current kinetic energy.
    thermostat.computeKineticEnergy();
    currentKineticEnergy = thermostat.getKineticEnergy();
    initialKinetic = currentKineticEnergy;
    currentTemperature = thermostat.getCurrentTemperature();
    initialTemp = currentTemperature;
    currentTotalEnergy = currentKineticEnergy + currentPotentialEnergy;
    initialTotal = currentTotalEnergy;

    temperatureStats.reset();
    potentialEnergyStats.reset();
    kineticEnergyStats.reset();
    totalEnergyStats.reset();
  }

  /** Pre-run operations (mostly logging) that require knowledge of system energy. */
  void postInitEnergies() {
    initialized = true;

    logger.log(
        basicLogging,
        format(
            "\n  %8s %12s %12s %12s %8s %8s",
            "Time", "Kinetic", "Potential", "Total", "Temp", "CPU"));
    logger.log(
        basicLogging,
        format(
            "  %8s %12s %12s %12s %8s %8s",
            "psec", "kcal/mol", "kcal/mol", "kcal/mol", "K", "sec"));
    logger.log(
        basicLogging,
        format(
            "  %8s %12.4f %12.4f %12.4f %8.2f",
            "",
            currentKineticEnergy,
            currentPotentialEnergy,
            currentTotalEnergy,
            currentTemperature));

    // Store the initialized state.
    storeState();
  }

  /** Post-run cleanup operations. */
  void postRun() {
    // Add the potential energy of the slow degrees of freedom.
    if (integrator instanceof Respa) {
      potential.setEnergyTermState(Potential.STATE.BOTH);
    }

    // Log normal completion.
    if (!terminate) {
      logger.log(basicLogging, format(" Completed %8d time steps\n", nSteps));
    }

    // Reset the done and terminate flags.
    done = true;
    terminate = false;
  }

  /**
   * Append a snapshot to the trajectory file.
   *
   * @param extraLines Strings of meta-data to include.
   */
  protected void appendSnapshot(String[] extraLines) {
    for (AssemblyInfo ai : assemblies) {
      if (ai.archiveFile != null && !saveSnapshotAsPDB) {
        String aiName = FileUtils.relativePathTo(ai.archiveFile).toString();
        if (esvSystem == null) {
          if (ai.xyzFilter.writeFile(ai.archiveFile, true, extraLines)) {
            logger.log(basicLogging, format(" Appended snap shot to %s", aiName));
          } else {
            logger.warning(format(" Appending snap shot to %s failed", aiName));
          }
        } else {
          if (ai.xphFilter.writeFile(ai.archiveFile, true, extraLines)) {
            logger.log(basicLogging, format(" Appended snap shot to %s", aiName));
          } else {
            logger.warning(format(" Appending snap shot to %s failed", aiName));
          }
        }

      } else if (saveSnapshotAsPDB) {
        String aiName = FileUtils.relativePathTo(ai.pdbFile).toString();
        if (ai.pdbFilter.writeFile(ai.pdbFile, true, extraLines)) {
          logger.log(basicLogging, format(" Wrote PDB file to %s", aiName));
        } else {
          logger.warning(format(" Writing PDB file to %s failed.", aiName));
        }
      }
    }
  }

  /**
   * Checks if thermodynamics must be logged. If logged, current time is returned, else the time
   * passed in is returned.
   *
   * @param step Timestep to possibly log thermodynamics for.
   * @param time Clock time (in nsec) thermodynamics was last logged.
   * @return Either current time (if logged), else the time variable passed in.
   */
  protected long logThermoForTime(long step, long time) {
    if (step % logFrequency == 0) {
      return logThermodynamics(time);
    } else {
      return time;
    }
  }

  /**
   * Log thermodynamics to the screen.
   *
   * @param time Clock time (in nsec) since this was last called.
   * @return Clock time at the end of this call.
   */
  private long logThermodynamics(long time) {
    time = System.nanoTime() - time;
    logger.log(
        basicLogging,
        format(" %7.3e %12.4f %12.4f %12.4f %8.2f %8.3f",
            totalSimTime,
            currentKineticEnergy,
            currentPotentialEnergy,
            currentTotalEnergy,
            currentTemperature,
            time * NS2SEC));
    return System.nanoTime();
  }

  /** Main loop of the run method. */
  private void mainLoop() {
    // Integrate Newton's equations of motion for the requested number of steps,
    // unless early termination is requested.
    long time = System.nanoTime();
    for (long step = 1; step <= nSteps; step++) {
      if (step > 1) {
        List<Constraint> constraints = potential.getConstraints();
        // TODO: Replace magic numbers with named constants.
        long constraintFails =
            constraints.stream().filter((Constraint c) -> !c.constraintSatisfied(x, v, 1E-7, 1E-7))
                .count();
        if (constraintFails > 0) {
          logger.info(format(" %d constraint failures in step %d", constraintFails, step));
        }
      }

      // Do the half-step thermostat operation.
      thermostat.halfStep(dt);

      // Do the half-step integration operation.
      integrator.preForce(potential);
      if (esvSystem != null) {
        esvIntegrator.preForce(potential);
        //preForce processes theta values after
        esvSystem.preForce();
      }

      // Compute the potential energy and gradients.
      double priorPE = currentPotentialEnergy;
      try {
        if (esvSystem != null && potential instanceof ForceFieldEnergyOpenMM) {
          currentPotentialEnergy = ((ForceFieldEnergyOpenMM) potential).energyAndGradientFFX(x,
              gradient);
        } else {
          currentPotentialEnergy = potential.energyAndGradient(x, gradient);
        }
      } catch (EnergyException ex) {
        writeStoredSnapshots();
        throw ex;
      }

      // Add the potential energy of the slow degrees of freedom.
      if (integrator instanceof Respa) {
        Respa r = (Respa) integrator;
        currentPotentialEnergy += r.getHalfStepEnergy();
      }

      double defaultDeltaPEThresh = 1.0E6;
      detectAtypicalEnergy(priorPE, defaultDeltaPEThresh);

      // Do the full-step integration operation.
      integrator.postForce(gradient);
      if (esvSystem != null) {
        double[] dEdL = esvSystem.postForce();
        esvIntegrator.postForce(dEdL);
      }

      // Compute the full-step kinetic energy.
      thermostat.computeKineticEnergy();

      // Do the full-step thermostat operation.
      thermostat.fullStep(dt);

      // Recompute the kinetic energy after the full-step thermostat operation.
      thermostat.computeKineticEnergy();
      if (esvSystem != null) {
        //Adiabatic thermostat does nothing at half step.
        esvThermostat.computeKineticEnergy();
      }

      // Remove center of mass motion every ~100 steps.
      int removeCOMMotionFrequency = 100;
      if (thermostat.getRemoveCenterOfMassMotion() && step % removeCOMMotionFrequency == 0) {
        thermostat.centerOfMassMotion(true, false);
      }

      // Collect current kinetic energy, temperature, and total energy.
      currentKineticEnergy = thermostat.getKineticEnergy();
      if (esvSystem != null) {
        currentKineticEnergy += esvThermostat.getKineticEnergy();
      }
      currentTemperature = thermostat.getCurrentTemperature();
      currentTotalEnergy = currentKineticEnergy + currentPotentialEnergy;

      // Collect running statistics.
      temperatureStats.addValue(currentTemperature);
      potentialEnergyStats.addValue(currentPotentialEnergy);
      kineticEnergyStats.addValue(currentKineticEnergy);
      totalEnergyStats.addValue(currentTotalEnergy);

      // Update atomic velocity, acceleration and previous acceleration.
      potential.setVelocity(v);
      potential.setAcceleration(a);
      potential.setPreviousAcceleration(aPrevious);

      // Log the current state every printFrequency steps.
      totalSimTime += dt;
      time = logThermoForTime(step, time);
      if (step % printEsvFrequency == 0 && esvSystem != null) {
        logger.log(basicLogging, format(" %s", esvSystem.getLambdaList()));
      }

      if (automaticWriteouts) {
        writeFilesForStep(step, true, true);
      }

      // Notify the algorithmListeners.
      if (algorithmListener != null && step % logFrequency == 0) {
        for (AssemblyInfo assembly : assemblies) {
          algorithmListener.algorithmUpdate(assembly.getAssembly());
        }
      }

      // Check for a termination request.
      if (terminate) {
        logger.info(format("\n Terminating after %8d time steps\n", step));
        break;
      }
    }
  }

  /**
   * Detects grossly atypical potential energy values that are likely incorrect, and writes snapshots
   * to disc. "Grossly atypical" is defined as: greater than 1.0E100 kcal/mol, less than -1.0E100
   * kcal/mol, non-finite (NaN/infinite), or exceeding specified delta from the prior potential
   * energy.
   *
   * <p>After prior snapshots have been written to disc, the queue they are stored on is now empty,
   * preventing printing of duplicate snapshots.
   *
   * @param priorPE Potential energy prior to this step.
   * @param delPEThresh If potential energy changes by this much, trigger a
   */
  void detectAtypicalEnergy(double priorPE, double delPEThresh) {

    // If not keeping snapshots, disable functionality.
    if (numSnapshotsToKeep < 1) {
      return;
    }

    double deltaPE = currentPotentialEnergy - priorPE;

    CoordinateSnapshot currState = new CoordinateSnapshot();
    currState.storeState();
    lastSnapshots.add(currState);

    double maxPEThresh = 1.0E100; // 1.0E100 kcal/mol is well into the territory of the absurd.
    double absPE = Math.abs(currentPotentialEnergy);

    if (absPE > maxPEThresh
        || !Double.isFinite(currentPotentialEnergy)
        || Math.abs(deltaPE) > delPEThresh) {
      logger.info(
          format(
              " Unusual potential energy %12.5g detected, writing snapshots.",
              currentPotentialEnergy));
      writeStoredSnapshots();
      currState
          .revertState(); // May be unnecessary, thanks to the current state always being last on
      // the queue.
      if (absPE > 1.0E100 || !Double.isFinite(currentPotentialEnergy)) {
        logger.severe(
            format(
                " Dynamics exiting with atypical potential energy of %12.5g",
                currentPotentialEnergy));
      }
    }
  }

  /**
   * Perform a sanity check on a frequency to ensure it's not longer than total runtime. Currently,
   * the setter parameter is ignored due to issues with our test suite.
   *
   * @param describe Description of the frequency.
   * @param frequency Frequency in timesteps.
   * @param setter Currently ignored.
   */
  private void sanityCheckFrequency(String describe, int frequency, DoubleConsumer setter) {
    if (frequency > nSteps) {
      logger.fine(format(" Specified %s frequency of %d is greater than the number of steps %d",
          describe, frequency, nSteps));
    }
  }

  /**
   * Set the coordinates.
   *
   * @param coords The coordinates to copy into MD coordinates array.
   */
  public void setCoordinates(double[] coords) {
    if (coords.length == x.length) {
      System.arraycopy(coords, 0, x, 0, x.length);
    }
  }

  /**
   * Get the coordinates.
   *
   * @return A copy of the current coordinates are returned.
   */
  public double[] getCoordinates() {
    return Arrays.copyOf(x, x.length);
  }

  public enum VerbosityLevel {
    VERBOSE(false),
    QUIET(true),
    SILENT(true);

    private final boolean isQuiet;

    VerbosityLevel(boolean isQuiet) {
      this.isQuiet = isQuiet;
    }

    public boolean isQuiet() {
      return isQuiet;
    }
  }

  /** Describes actions taken by writeFilesForStep */
  public enum WriteActions {
    // TODO: Flesh this out if more functionality is needed.
    RESTART,
    SNAPSHOT
  }

  /**
   * Enumerates available molecular dynamics engines; presently limited to the FFX reference engine
   * and the OpenMM engine.
   *
   * <p>Distinct from the force field energy Platform, as the FFX engine can use OpenMM energies,
   * but not vice-versa.
   */
  public enum DynamicsEngine {
    FFX(true, true),
    OMM(false, true),
    OPENMM(false, true);

    // Set of supported Platforms. The EnumSet paradigm is very efficient, as it
    // is internally stored as a bit field.
    private final EnumSet<ForceFieldEnergy.Platform> platforms =
        EnumSet.noneOf(ForceFieldEnergy.Platform.class);

    /**
     * Constructs a DynamicsEngine using the two presently known types of Platform.
     *
     * @param ffx Add support for the FFX reference energy platform.
     * @param openMM Add support for the OpenMM energy platforms.
     */
    DynamicsEngine(boolean ffx, boolean openMM) {
      if (ffx) {
        platforms.add(ForceFieldEnergy.Platform.FFX);
      }
      if (openMM) {
        platforms.add(ForceFieldEnergy.Platform.OMM);
        platforms.add(ForceFieldEnergy.Platform.OMM_REF);
        platforms.add(ForceFieldEnergy.Platform.OMM_CUDA);
        platforms.add(ForceFieldEnergy.Platform.OMM_OPENCL);
        platforms.add(ForceFieldEnergy.Platform.OMM_OPTCPU);
      }
    }

    /**
     * Gets the set of Platforms supported by this DynamicsEngine
     *
     * @return An EnumSet
     */
    public EnumSet<ForceFieldEnergy.Platform> getSupportedPlatforms() {
      return EnumSet.copyOf(platforms);
    }

    /**
     * Checks if this energy Platform is supported by this DynamicsEngine
     *
     * @param platform The requested platform.
     * @return If supported
     */
    public boolean supportsPlatform(ForceFieldEnergy.Platform platform) {
      return platforms.contains(platform);
    }
  }

  /**
   * A simple container class to hold all the infrastructure associated with a MolecularAssembly for
   * MolecularDynamics.
   */
  protected static class AssemblyInfo {

    private final MolecularAssembly assembly;
    CompositeConfiguration compositeConfiguration;
    File archiveFile = null;
    XYZFilter xyzFilter = null;
    XPHFilter xphFilter;
    File pdbFile;
    PDBFilter pdbFilter;

    AssemblyInfo(MolecularAssembly assembly) {
      this.assembly = assembly;
      pdbFile = SystemFilter.version(assembly.getFile());
      compositeConfiguration = assembly.getProperties();
      pdbFilter = new PDBFilter(pdbFile, assembly,
          assembly.getForceField(),
          assembly.getProperties());
      // Turn on use of MODEL records.
      pdbFilter.setModelNumbering(0);
    }

    public MolecularAssembly getAssembly() {
      return assembly;
    }

    void setArchiveFile(File file) {
      archiveFile = file;
    }
  }

  /**
   * More limited version of a DynamicsState, storing only coordinates. TODO: Make DynamicsState more
   * flexible and let it store any combination of variables.
   */
  protected class CoordinateSnapshot {

    final double[] xBak;

    CoordinateSnapshot() {
      xBak = new double[numberOfVariables];
    }

    void storeState() {
      arraycopy(x, 0, xBak, 0, numberOfVariables);
    }

    void revertState() {
      arraycopy(xBak, 0, x, 0, numberOfVariables);
      Atom[] atoms = molecularAssembly.getActiveAtomArray();
      for (int i = 0; i < atoms.length; i++) {
        int i3 = 3 * i;
        double[] newXYZ = new double[3];
        arraycopy(xBak, i3, newXYZ, 0, 3);
        atoms[i].setXYZ(newXYZ);
      }
    }
  }

  protected class DynamicsState {

    final double[] xBak;
    final double[] vBak;
    final double[] aBak;
    final double[] aPreviousBak;
    final double[] massBak;
    final double[] gradBak;
    double currentKineticEnergyBak, currentPotentialEnergyBak, currentTotalEnergyBak;
    double currentTemperatureBak;

    DynamicsState() {
      xBak = new double[numberOfVariables];
      vBak = new double[numberOfVariables];
      aBak = new double[numberOfVariables];
      aPreviousBak = new double[numberOfVariables];
      massBak = new double[numberOfVariables];
      gradBak = new double[numberOfVariables];
    }

    public void describe(String title) {
      StringBuilder sb = new StringBuilder();
      sb.append(title);
      sb.append("\nx: ");
      Arrays.stream(x).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append("\nv: ");
      Arrays.stream(v).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append("\na: ");
      Arrays.stream(a).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append("\naP: ");
      Arrays.stream(aPrevious).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append("\nm: ");
      Arrays.stream(mass).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append("\ng: ");
      Arrays.stream(gradient).forEach(val -> sb.append(format("%.2g, ", val)));
      sb.append(
          format(
              "\nK,U,E,T: %g %g %g %g\n",
              currentKineticEnergy,
              currentPotentialEnergy,
              currentTotalEnergy,
              currentTemperature));
      logger.info(sb.toString());
    }

    public void revertState() {
      if (verboseDynamicsState) {
        describe(" Reverting State (From):");
      }
      currentKineticEnergy = currentKineticEnergyBak;
      currentPotentialEnergy = currentPotentialEnergyBak;
      currentTotalEnergy = currentTotalEnergyBak;
      currentTemperature = currentTemperatureBak;
      arraycopy(xBak, 0, x, 0, numberOfVariables);
      arraycopy(vBak, 0, v, 0, numberOfVariables);
      arraycopy(aBak, 0, a, 0, numberOfVariables);
      arraycopy(aPreviousBak, 0, aPrevious, 0, numberOfVariables);
      arraycopy(massBak, 0, mass, 0, numberOfVariables);
      arraycopy(gradBak, 0, gradient, 0, numberOfVariables);

      Atom[] atoms = molecularAssembly.getActiveAtomArray();
      if (atoms.length * 3 == numberOfVariables) {
        double[] vec = new double[3];
        int index = 0;
        for (Atom atom : atoms) {
          atom.moveTo(x[index], x[index + 1], x[index + 2]);
          atom.setXYZGradient(gradient[index], gradient[index + 1], gradient[index + 2]);
          vec[0] = v[index];
          vec[1] = v[index + 1];
          vec[2] = v[index + 2];
          atom.setVelocity(vec);
          vec[0] = a[index];
          vec[1] = a[index + 1];
          vec[2] = a[index + 2];
          atom.setAcceleration(vec);
          vec[0] = aPrevious[index];
          vec[1] = aPrevious[index + 1];
          vec[2] = aPrevious[index + 2];
          atom.setPreviousAcceleration(vec);
          index += 3;
        }
      }
      if (verboseDynamicsState) {
        describe(" Reverting State (To):");
      }
    }

    public void storeState() {
      currentKineticEnergyBak = currentKineticEnergy;
      currentPotentialEnergyBak = currentPotentialEnergy;
      currentTotalEnergyBak = currentTotalEnergy;
      currentTemperatureBak = currentTemperature;
      arraycopy(x, 0, xBak, 0, numberOfVariables);
      arraycopy(v, 0, vBak, 0, numberOfVariables);
      arraycopy(a, 0, aBak, 0, numberOfVariables);
      arraycopy(aPrevious, 0, aPreviousBak, 0, numberOfVariables);
      arraycopy(mass, 0, massBak, 0, numberOfVariables);
      arraycopy(gradient, 0, gradBak, 0, numberOfVariables);
      if (verboseDynamicsState) {
        describe(" Storing State:");
      }
    }
  }
}
