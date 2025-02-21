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

// Substantial sections ported from OpenMM.

/* -------------------------------------------------------------------------- *
 *                                   OpenMM                                   *
 * -------------------------------------------------------------------------- *
 * This is part of the OpenMM molecular simulation toolkit originating from   *
 * Simbios, the NIH National Center for Physics-Based Simulation of           *
 * Biological Structures at Stanford, funded under the NIH Roadmap for        *
 * Medical Research, grant U54 GM072970. See https://simtk.org.               *
 *                                                                            *
 * Portions copyright (c) 2013 Stanford University and the Authors.           *
 * Authors: Peter Eastman                                                     *
 * Contributors:                                                              *
 *                                                                            *
 * Permission is hereby granted, free of charge, to any person obtaining a    *
 * copy of this software and associated documentation files (the "Software"), *
 * to deal in the Software without restriction, including without limitation  *
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,   *
 * and/or sell copies of the Software, and to permit persons to whom the      *
 * Software is furnished to do so, subject to the following conditions:       *
 *                                                                            *
 * The above copyright notice and this permission notice shall be included in *
 * all copies or substantial portions of the Software.                        *
 *                                                                            *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR *
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,   *
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL    *
 * THE AUTHORS, CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,    *
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR      *
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE  *
 * USE OR OTHER DEALINGS IN THE SOFTWARE.                                     *
 * -------------------------------------------------------------------------- */

package ffx.potential.constraint;

import static ffx.numerics.math.DoubleMath.dot;
import static ffx.numerics.math.DoubleMath.normalize;
import static ffx.numerics.math.DoubleMath.sub;
import static java.lang.System.arraycopy;
import static org.apache.commons.math3.util.FastMath.abs;
import static org.apache.commons.math3.util.FastMath.cos;
import static org.apache.commons.math3.util.FastMath.sqrt;
import static org.apache.commons.math3.util.FastMath.toRadians;

import ffx.numerics.Constraint;
import ffx.potential.bonded.Angle;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.Bond;
import java.util.logging.Logger;

/**
 * SETTLE triatomic distance constraints, intended for rigid water models.
 *
 * <p>S. Miyamoto and P.A. Kollman, "SETTLE: An Analytical Version of the SHAKE and RATTLE Algorithm
 * for Rigid Water Models", Journal of Computational Chemistry, Vol. 13, No. 8, 952-962 (1992)
 *
 * @author Michael J. Schnieders
 * @author Jacob M. Litman
 * @since 1.0
 */
public class SettleConstraint implements Constraint {

  private static final Logger logger = Logger.getLogger(SettleConstraint.class.getName());

  private final int index0;
  private final int index1;
  private final int index2;
  // Typically the O-H bond length.
  private final double distance1;
  // Typically a fictitious H-H bond length.
  private final double distance2;

  /**
   * Constructs a SETTLE constraint from an angle and its two bonds. Does not inform a012 that it is
   * now constrained, which is why there is a factory method wrapping the private constructor.
   *
   * @param a012 Angle to construct a SETTLE constraint from.
   */
  private SettleConstraint(Angle a012) {
    Atom center = a012.getCentralAtom();
    index0 = center.getXyzIndex() - 1;

    Bond b01 = a012.getBond(0);
    Bond b02 = a012.getBond(1);
    // TODO: Determine if SETTLE is possible if this is false.
    assert b01.bondType.distance == b02.bondType.distance;

    distance1 = b01.bondType.distance;

    Atom a1 = b01.get1_2(center);
    Atom a2 = b02.get1_2(center);
    index1 = a1.getXyzIndex() - 1;
    index2 = a2.getXyzIndex() - 1;

    double angVal = a012.angleType.angle[a012.nh];
    distance2 = lawOfCosines(distance1, distance1, angVal);
  }

  /**
   * Constructs a SETTLE constraint from an angle and its two bonds. Factory used mostly to avoid a
   * leaking-this scenario, as this method also passes the new SETTLE constraint to a012.
   *
   * @param a012 Angle to construct a SETTLE constraint from.
   * @return New SettleConstraint.
   */
  public static SettleConstraint settleFactory(Angle a012) {
    SettleConstraint newC = new SettleConstraint(a012);
    a012.setConstraint(newC);
    return newC;
  }

  /**
   * Calculates the distance AC based on known side lengths AB, BC, and angle ABC. Intended to get
   * the H-H pseudo-bond for rigid water molecules.
   *
   * @param distAB Distance between points A and B
   * @param distBC Distance between points B and C
   * @param angABC Angle between points A, B, and C, in degrees.
   * @return Distance between points A and C
   */
  static double lawOfCosines(double distAB, double distBC, double angABC) {
    double val = distAB * distAB;
    val += distBC * distBC;
    val -= (2.0 * distAB * distBC * cos(toRadians(angABC)));
    val = sqrt(val);
    return val;
  }

  @Override
  public void applyConstraintToStep(
      final double[] xPrior, double[] xNew, final double[] masses, double tol) {
    // Ported from OpenMM's ReferenceSETTLEAlgorithm.cpp
    // Pulled from OpenMM commit a783b996fc42d023ebfd17c5591508da01dde03a

    // Mapping from atom index to the start of their Cartesian coordinates.
    int xi0 = 3 * index0;
    int xi1 = 3 * index1;
    int xi2 = 3 * index2;

    // Initial positions of the constrained atoms.
    double[] apos0 = new double[3];
    double[] apos1 = new double[3];
    double[] apos2 = new double[3];

    // Deltas from the original state (xPrior) to the partially calculated new state (xNew).
    double[] xp0 = new double[3];
    double[] xp1 = new double[3];
    double[] xp2 = new double[3];

    for (int i = 0; i < 3; i++) {
      apos0[i] = xPrior[xi0 + i];
      xp0[i] = xNew[xi0 + i] - apos0[i];
      apos1[i] = xPrior[xi1 + i];
      xp1[i] = xNew[xi1 + i] - apos1[i];
      apos2[i] = xPrior[xi2 + i];
      xp2[i] = xNew[xi2 + i] - apos2[i];
    }

    double m0 = masses[xi0];
    double m1 = masses[xi1];
    double m2 = masses[xi2];

    // Apply the SETTLE algorithm.

    double xb0 = apos1[0] - apos0[0];
    double yb0 = apos1[1] - apos0[1];
    double zb0 = apos1[2] - apos0[2];
    double xc0 = apos2[0] - apos0[0];
    double yc0 = apos2[1] - apos0[1];
    double zc0 = apos2[2] - apos0[2];

    double invTotalMass = 1.0 / (m0 + m1 + m2);
    double xcom = (xp0[0] * m0 + (xb0 + xp1[0]) * m1 + (xc0 + xp2[0]) * m2) * invTotalMass;
    double ycom = (xp0[1] * m0 + (yb0 + xp1[1]) * m1 + (yc0 + xp2[1]) * m2) * invTotalMass;
    double zcom = (xp0[2] * m0 + (zb0 + xp1[2]) * m1 + (zc0 + xp2[2]) * m2) * invTotalMass;

    double xa1 = xp0[0] - xcom;
    double ya1 = xp0[1] - ycom;
    double za1 = xp0[2] - zcom;
    double xb1 = xb0 + xp1[0] - xcom;
    double yb1 = yb0 + xp1[1] - ycom;
    double zb1 = zb0 + xp1[2] - zcom;
    double xc1 = xc0 + xp2[0] - xcom;
    double yc1 = yc0 + xp2[1] - ycom;
    double zc1 = zc0 + xp2[2] - zcom;

    double xaksZd = yb0 * zc0 - zb0 * yc0;
    double yaksZd = zb0 * xc0 - xb0 * zc0;
    double zaksZd = xb0 * yc0 - yb0 * xc0;
    double xaksXd = ya1 * zaksZd - za1 * yaksZd;
    double yaksXd = za1 * xaksZd - xa1 * zaksZd;
    double zaksXd = xa1 * yaksZd - ya1 * xaksZd;
    double xaksYd = yaksZd * zaksXd - zaksZd * yaksXd;
    double yaksYd = zaksZd * xaksXd - xaksZd * zaksXd;
    double zaksYd = xaksZd * yaksXd - yaksZd * xaksXd;

    double axlng = sqrt(xaksXd * xaksXd + yaksXd * yaksXd + zaksXd * zaksXd);
    double aylng = sqrt(xaksYd * xaksYd + yaksYd * yaksYd + zaksYd * zaksYd);
    double azlng = sqrt(xaksZd * xaksZd + yaksZd * yaksZd + zaksZd * zaksZd);
    double trns11 = xaksXd / axlng;
    double trns21 = yaksXd / axlng;
    double trns31 = zaksXd / axlng;
    double trns12 = xaksYd / aylng;
    double trns22 = yaksYd / aylng;
    double trns32 = zaksYd / aylng;
    double trns13 = xaksZd / azlng;
    double trns23 = yaksZd / azlng;
    double trns33 = zaksZd / azlng;

    double xb0d = trns11 * xb0 + trns21 * yb0 + trns31 * zb0;
    double yb0d = trns12 * xb0 + trns22 * yb0 + trns32 * zb0;
    double xc0d = trns11 * xc0 + trns21 * yc0 + trns31 * zc0;
    double yc0d = trns12 * xc0 + trns22 * yc0 + trns32 * zc0;
    double za1d = trns13 * xa1 + trns23 * ya1 + trns33 * za1;
    double xb1d = trns11 * xb1 + trns21 * yb1 + trns31 * zb1;
    double yb1d = trns12 * xb1 + trns22 * yb1 + trns32 * zb1;
    double zb1d = trns13 * xb1 + trns23 * yb1 + trns33 * zb1;
    double xc1d = trns11 * xc1 + trns21 * yc1 + trns31 * zc1;
    double yc1d = trns12 * xc1 + trns22 * yc1 + trns32 * zc1;
    double zc1d = trns13 * xc1 + trns23 * yc1 + trns33 * zc1;

    //                                        --- Step2  A2' ---

    double rc = 0.5 * distance2;
    double rb = sqrt(distance1 * distance1 - rc * rc);
    double ra = rb * (m1 + m2) * invTotalMass;
    rb -= ra;
    double sinphi = za1d / ra;
    double cosphi = sqrt(1 - sinphi * sinphi);
    double sinpsi = (zb1d - zc1d) / (2 * rc * cosphi);
    double cospsi = sqrt(1 - sinpsi * sinpsi);

    double ya2d = ra * cosphi;
    double xb2d = -rc * cospsi;
    double yb2d = -rb * cosphi - rc * sinpsi * sinphi;
    double yc2d = -rb * cosphi + rc * sinpsi * sinphi;
    double xb2d2 = xb2d * xb2d;
    double hh2 = 4.0f * xb2d2 + (yb2d - yc2d) * (yb2d - yc2d) + (zb1d - zc1d) * (zb1d - zc1d);
    double deltx = 2.0f * xb2d + sqrt(4.0f * xb2d2 - hh2 + distance2 * distance2);
    xb2d -= deltx * 0.5;

    //                                        --- Step3  al,be,ga ---

    double alpha = (xb2d * (xb0d - xc0d) + yb0d * yb2d + yc0d * yc2d);
    double beta = (xb2d * (yc0d - yb0d) + xb0d * yb2d + xc0d * yc2d);
    double gamma = xb0d * yb1d - xb1d * yb0d + xc0d * yc1d - xc1d * yc0d;

    double al2be2 = alpha * alpha + beta * beta;
    double sintheta = (alpha * gamma - beta * sqrt(al2be2 - gamma * gamma)) / al2be2;

    //                                        --- Step4  A3' ---

    double costheta = sqrt(1 - sintheta * sintheta);
    double xa3d = -ya2d * sintheta;
    double ya3d = ya2d * costheta;
    double za3d = za1d;
    double xb3d = xb2d * costheta - yb2d * sintheta;
    double yb3d = xb2d * sintheta + yb2d * costheta;
    double zb3d = zb1d;
    double xc3d = -xb2d * costheta - yc2d * sintheta;
    double yc3d = -xb2d * sintheta + yc2d * costheta;
    double zc3d = zc1d;

    //                                        --- Step5  A3 ---

    double xa3 = trns11 * xa3d + trns12 * ya3d + trns13 * za3d;
    double ya3 = trns21 * xa3d + trns22 * ya3d + trns23 * za3d;
    double za3 = trns31 * xa3d + trns32 * ya3d + trns33 * za3d;
    double xb3 = trns11 * xb3d + trns12 * yb3d + trns13 * zb3d;
    double yb3 = trns21 * xb3d + trns22 * yb3d + trns23 * zb3d;
    double zb3 = trns31 * xb3d + trns32 * yb3d + trns33 * zb3d;
    double xc3 = trns11 * xc3d + trns12 * yc3d + trns13 * zc3d;
    double yc3 = trns21 * xc3d + trns22 * yc3d + trns23 * zc3d;
    double zc3 = trns31 * xc3d + trns32 * yc3d + trns33 * zc3d;

    xp0[0] = xcom + xa3;
    xp0[1] = ycom + ya3;
    xp0[2] = zcom + za3;
    xp1[0] = xcom + xb3 - xb0;
    xp1[1] = ycom + yb3 - yb0;
    xp1[2] = zcom + zb3 - zb0;
    xp2[0] = xcom + xc3 - xc0;
    xp2[1] = ycom + yc3 - yc0;
    xp2[2] = zcom + zc3 - zc0;

    for (int i = 0; i < 3; i++) {
      xNew[xi0 + i] = xp0[i] + apos0[i];
      xNew[xi1 + i] = xp1[i] + apos1[i];
      xNew[xi2 + i] = xp2[i] + apos2[i];
    }
  }

  @Override
  public void applyConstraintToVelocities(
      final double[] x, double[] v, final double[] masses, double tol) {
    // Ported from OpenMM's ReferenceSETTLEAlgorithm.cpp
    // Pulled from OpenMM commit a783b996fc42d023ebfd17c5591508da01dde03a

    // Mapping from atom index to the start of their Cartesian coordinates.
    int xi0 = 3 * index0;
    int xi1 = 3 * index1;
    int xi2 = 3 * index2;

    // Positions of the constrained atoms.
    double[] apos0 = new double[3];
    double[] apos1 = new double[3];
    double[] apos2 = new double[3];

    // Pre-constraint velocities.
    double[] v0 = new double[3];
    double[] v1 = new double[3];
    double[] v2 = new double[3];

    for (int i = 0; i < 3; i++) {
      apos0[i] = x[xi0 + i];
      apos1[i] = x[xi1 + i];
      apos2[i] = x[xi2 + i];
    }

    for (int i = 0; i < 3; i++) {
      v0[i] = v[xi0 + i];
      v1[i] = v[xi1 + i];
      v2[i] = v[xi2 + i];
    }

    double mA = masses[xi0];
    double mB = masses[xi1];
    double mC = masses[xi2];

    double[] eAB = new double[3];
    double[] eBC = new double[3];
    double[] eCA = new double[3];
    for (int i = 0; i < 3; i++) {
      eAB[i] = apos1[i] - apos0[i];
      eBC[i] = apos2[i] - apos1[i];
      eCA[i] = apos0[i] - apos2[i];
    }
    normalize(eAB, eAB);
    normalize(eBC, eBC);
    normalize(eCA, eCA);
    /*eAB /= sqrt(eAB[0]*eAB[0] + eAB[1]*eAB[1] + eAB[2]*eAB[2]);
    eBC /= sqrt(eBC[0]*eBC[0] + eBC[1]*eBC[1] + eBC[2]*eBC[2]);
    eCA /= sqrt(eCA[0]*eCA[0] + eCA[1]*eCA[1] + eCA[2]*eCA[2]);*/
    double vAB = (v1[0] - v0[0]) * eAB[0] + (v1[1] - v0[1]) * eAB[1] + (v1[2] - v0[2]) * eAB[2];
    double vBC = (v2[0] - v1[0]) * eBC[0] + (v2[1] - v1[1]) * eBC[1] + (v2[2] - v1[2]) * eBC[2];
    double vCA = (v0[0] - v2[0]) * eCA[0] + (v0[1] - v2[1]) * eCA[1] + (v0[2] - v2[2]) * eCA[2];
    double cA = -(eAB[0] * eCA[0] + eAB[1] * eCA[1] + eAB[2] * eCA[2]);
    double cB = -(eAB[0] * eBC[0] + eAB[1] * eBC[1] + eAB[2] * eBC[2]);
    double cC = -(eBC[0] * eCA[0] + eBC[1] * eCA[1] + eBC[2] * eCA[2]);
    double s2A = 1 - cA * cA;
    double s2B = 1 - cB * cB;
    double s2C = 1 - cC * cC;

    // Solve the equations.  These are different from those in the SETTLE paper (JCC 13(8), pp.
    // 952-962, 1992), because
    // in going from equations B1 to B2, they make the assumption that mB=mC (but don't bother to
    // mention they're
    // making that assumption).  We allow all three atoms to have different masses.

    double mABCinv = 1 / (mA * mB * mC);
    double denom =
        (((s2A * mB + s2B * mA) * mC
            + (s2A * mB * mB + 2 * (cA * cB * cC + 1) * mA * mB + s2B * mA * mA))
            * mC
            + s2C * mA * mB * (mA + mB))
            * mABCinv;
    double tab =
        ((cB * cC * mA - cA * mB - cA * mC) * vCA
            + (cA * cC * mB - cB * mC - cB * mA) * vBC
            + (s2C * mA * mA * mB * mB * mABCinv + (mA + mB + mC)) * vAB)
            / denom;
    double tbc =
        ((cA * cB * mC - cC * mB - cC * mA) * vCA
            + (s2A * mB * mB * mC * mC * mABCinv + (mA + mB + mC)) * vBC
            + (cA * cC * mB - cB * mA - cB * mC) * vAB)
            / denom;
    double tca =
        ((s2B * mA * mA * mC * mC * mABCinv + (mA + mB + mC)) * vCA
            + (cA * cB * mC - cC * mB - cC * mA) * vBC
            + (cB * cC * mA - cA * mB - cA * mC) * vAB)
            / denom;

    double invMA = 1.0 / mA;
    double invMB = 1.0 / mB;
    double invMC = 1.0 / mC;
    for (int i = 0; i < 3; i++) {
      v0[i] += (eAB[i] * tab - eCA[i] * tca) * invMA;
      v1[i] += (eBC[i] * tbc - eAB[i] * tab) * invMB;
      v2[i] += (eCA[i] * tca - eBC[i] * tbc) * invMC;
    }
    /*v0 += (eAB*tab - eCA*tca)*inverseMasses[atom1[index]];
    v1 += (eBC*tbc - eAB*tab)*inverseMasses[atom2[index]];
    v2 += (eCA*tca - eBC*tbc)*inverseMasses[atom3[index]];*/

    arraycopy(v0, 0, v, xi0, 3);
    arraycopy(v1, 0, v, xi1, 3);
    arraycopy(v2, 0, v, xi2, 3);
    /* velocities[atom1[index]] = v0;
    velocities[atom2[index]] = v1;
    velocities[atom3[index]] = v2; */
  }

  @Override
  public int[] constrainedAtomIndices() {
    return new int[] {index0, index1, index2};
  }

  @Override
  public boolean constraintSatisfied(double[] x, double tol) {
    return constraintSatisfied(x, null, tol, 0.0);
  }

  @Override
  public boolean constraintSatisfied(double[] x, double[] v, double xTol, double vTol) {
    int xi0 = 3 * index0;
    int xi1 = 3 * index1;
    int xi2 = 3 * index2;

    // O-H bonds.
    double dist01 = 0;
    double dist02 = 0;
    // H-H pseudo-bond.
    double dist12 = 0;

    double[] x0 = new double[3];
    double[] x1 = new double[3];
    double[] x2 = new double[3];
    arraycopy(x, xi0, x0, 0, 3);
    arraycopy(x, xi1, x1, 0, 3);
    arraycopy(x, xi2, x2, 0, 3);

    for (int i = 0; i < 3; i++) {
      // 0-1 bond.
      double dx = x0[i] - x1[i];
      dx *= dx;
      dist01 += dx;

      // 0-2 bond.
      dx = x0[i] - x2[i];
      dx *= dx;
      dist02 += dx;

      // 1-2 bond.
      dx = x1[i] - x2[i];
      dx *= dx;
      dist12 += dx;
    }
    dist01 = sqrt(dist01);
    dist02 = sqrt(dist02);
    dist12 = sqrt(dist12);

    // Check that positions are satisfied.
    double deltaIdeal = Math.abs((dist01 - distance1) / distance1);
    if (deltaIdeal > xTol) {
      logger.finer(" delId 01: " + deltaIdeal);
      return false;
    }
    deltaIdeal = Math.abs((dist02 - distance1) / distance1);
    if (deltaIdeal > xTol) {
      logger.finer(" delId 02: " + deltaIdeal);
      return false;
    }
    deltaIdeal = Math.abs((dist12 - distance2) / distance2);
    if (deltaIdeal > xTol) {
      logger.finer(" delId 12: " + deltaIdeal);
      return false;
    }

    if (v != null && vTol > 0) {
      double[] v0 = new double[3];
      double[] v1 = new double[3];
      double[] v2 = new double[3];
      arraycopy(v, xi0, v0, 0, 3);
      arraycopy(v, xi1, v1, 0, 3);
      arraycopy(v, xi2, v2, 0, 3);

      // Obtain relative velocities.
      double[] v01 = new double[3];
      double[] v02 = new double[3];
      double[] v12 = new double[3];
      sub(v1, v0, v01);
      sub(v2, v0, v02);
      sub(v2, v1, v12);

      // Obtain bond vectors.
      double[] x01 = new double[3];
      double[] x02 = new double[3];
      double[] x12 = new double[3];
      sub(x1, x0, x01);
      sub(x2, x0, x02);
      sub(x2, x1, x12);

      double xv01 = dot(v01, x01);
      double xv02 = dot(v02, x02);
      double xv12 = dot(v12, x12);

      if (abs(xv01) > vTol) {
        return false;
      }
      if (abs(xv02) > vTol) {
        return false;
      }
      if (abs(xv12) > vTol) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int getNumDegreesFrozen() {
    return 3; // 2 bonds and an angle are frozen; for water, this leaves just 3x rotation 3x
    // translation DoF.
  }
}
