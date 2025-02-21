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
package ffx.algorithms.dynamics.integrators;

import static ffx.utilities.Constants.KCAL_TO_GRAM_ANG2_PER_PS2;
import static java.lang.System.arraycopy;
import static java.util.Arrays.copyOf;

import ffx.numerics.Constraint;
import ffx.numerics.Potential;

/**
 * Integrate Newton's equations of motion using a Velocity Verlet multistep recursion formula.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
public class VelocityVerlet extends Integrator {

  private double[] xPrior;

  /**
   * Constructor for VelocityVerlet.
   *
   * @param nVariables number of Variables.
   * @param x Cartesian coordinates (Angstroms).
   * @param v Velocities.
   * @param a Accelerations.
   * @param mass Mass.
   */
  public VelocityVerlet(int nVariables, double[] x, double[] v, double[] a, double[] mass) {
    super(nVariables, x, v, a, null, mass);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Use Newton's second law to find accelerations and then full-step velocities.
   */
  @Override
  public void postForce(double[] gradient) {
    copyAccelerationToPrevious();
    for (int i = 0; i < nVariables; i++) {
      a[i] = -KCAL_TO_GRAM_ANG2_PER_PS2 * gradient[i] / mass[i];
      v[i] = v[i] + a[i] * dt_2;
    }
    constraints.forEach(
        (Constraint c) -> c.applyConstraintToVelocities(x, v, mass, constraintTolerance));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Find half-step velocities and full-step positions.
   */
  @Override
  public void preForce(Potential potential) {
    if (useConstraints) {
      if (xPrior == null) {
        xPrior = copyOf(x, nVariables);
      } else {
        arraycopy(x, 0, xPrior, 0, nVariables);
      }
    }
    for (int i = 0; i < nVariables; i++) {
      v[i] = v[i] + a[i] * dt_2;
      x[i] = x[i] + v[i] * dt;
    }
    if (useConstraints) {
      constraints.forEach(
          (Constraint c) -> c.applyConstraintToStep(xPrior, x, mass, constraintTolerance));
      double velScale = 1.0 / dt;
      for (int i = 0; i < nVariables; i++) {
        v[i] = velScale * (x[i] - xPrior[i]);
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void setTimeStep(double dt) {
    this.dt = dt;
    dt_2 = dt * 0.5;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return "Velocity Verlet";
  }
}
