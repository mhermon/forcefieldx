package ffx.potential.groovy

import ffx.potential.bonded.Atom
import ffx.potential.cli.PotentialScript
import ffx.potential.ANIEnergyModel
import picocli.CommandLine.Option
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/**
 * The ANIEnergy script evaluates the energy of a system according to the ANI models.
 * <br>
 * Usage:
 * <br>
 * ffxc ANIEnergy &lt;filename&gt;
 */

@Command(description = " Compute the ANI potential energy.", name = "ffxc ANIEnergy")
class ANIEnergy extends PotentialScript {

   /**
   * -m or --model ANI1x (1), ANI1ccx (1cc), or ANI2x (2).
   */
  @Option(names = ['-m', '--model'], paramLabel="ANI Model" , description = "The ANI model to use.", defaultValue = "1", 
      required = true, arity = "1")
  private String model;

  /**
   * -r.
   */
  @Option(names = ["-r", "--raw"], paramLabel="Raw Input" , description = "Use raw input?")
  private boolean raw;

  /**
   * The final argument is a PDB or XYZ coordinate file.
   */
  @Parameters(arity = "1", paramLabel = "file",
          description = 'The atomic coordinate file in PDB or XYZ format.')
  private String filename = null

  /**
   * ANIEnergy constructor.
   */
  ANIEnergy() {
    this(new Binding())
  }

  /**
   * ANIEnergy constructor.
   * @param binding The Groovy Binding to use.
   */
  ANIEnergy(Binding binding) {
    super(binding)
  }

  private ANIEnergyModel aniEnergyModel

  /**
   * Execute the script.
   */
  ANIEnergy run() {

    // Init the context and bind variables.
    if (!init()) {
      return this
    }

    // Load the MolecularAssembly.
    activeAssembly = getActiveAssembly(filename)
    if (activeAssembly == null) {
      logger.info(helpString())
      return this
    }

    // Set the filename.
    filename = activeAssembly.getFile().getAbsolutePath()

    logger.info("\n Running ANIEnergy on " + filename)

    List<Atom> atoms = activeAssembly.getAtomList()
    int numAtoms = atoms.size()

    float[] coordinates = new float[3*numAtoms]
    for (int i = 0; i < numAtoms; i++) {
      Atom atom = atoms[i]
      coordinates[3*i] = atom.getX()
      coordinates[3*i+1] = atom.getY()
      coordinates[3*i+2] = atom.getZ()
    }

    long[] species = new long[numAtoms]
    for (int i = 0; i < numAtoms; i++) {
      Atom atom = atoms[i]
      species[i] = atom.getAtomicNumber()
    }

    ANIEnergyModel energyModel = new ANIEnergyModel()
    energyModel.run(model, species, coordinates)

    return this
  }

}