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
package ffx.numerics.fft;

import static java.lang.Math.fma;
import static java.lang.System.arraycopy;
import static org.apache.commons.math3.util.FastMath.PI;
import static org.apache.commons.math3.util.FastMath.cos;
import static org.apache.commons.math3.util.FastMath.sin;
import static org.apache.commons.math3.util.FastMath.sqrt;

import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Compute the FFT of complex, double precision data of arbitrary length n. This class uses a mixed
 * radix method and has special methods to handle factors [2, 3, 4, 5, 6, 7] and a general method for
 * larger prime factors.
 *
 * @author Michal J. Schnieders<br> Derived from: <br> Bruce R. Miller (bruce.miller@nist.gov) <br>
 *     Contribution of the National Institute of Standards and Technology, not subject to copyright.
 *     <br>
 *     Derived from:<br> GSL (Gnu Scientific Library) FFT Code by Brian Gough
 *     (bjg@network-theory.co.uk)
 * @see <ul>
 *     <li><a href="http://dx.doi.org/10.1016/0021-9991(83)90013-X" target="_blank"> Clive
 *     Temperton. Self-sorting mixed-radix fast fourier transforms. Journal of Computational
 *     Physics, 52(1):1-23, 1983. </a>
 *     <li><a href="http://www.jstor.org/stable/2003354" target="_blank"> J. W. Cooley and J. W.
 *     Tukey, Mathematics of Computation 19 (90), 297 (1965) </a>
 *     <li><a href="http://en.wikipedia.org/wiki/Fast_Fourier_transform" target="_blank">FFT at
 *     Wikipedia </a>
 *     </ul>
 * @since 1.0
 */
public class Complex {

  private static final Logger logger = Logger.getLogger(Complex.class.getName());
  // TINKER v. 5.0 factors to achieve exact numerical agreement.
  // private static final int[] availableFactors = {5, 4, 3, 2};
  // private static final int firstUnavailablePrime = 7;
  private static final int[] availableFactors = {7, 6, 5, 4, 3, 2};
  private static final int firstUnavailablePrime = 11;
  private static final double oneThird = 1.0 / 3.0;
  private static final double sqrt3_2 = sqrt(3.0) / 2.0;
  private static final double sqrt5_4 = sqrt(5.0) / 4.0;
  private static final double sinPI_5 = sin(PI / 5.0);
  private static final double sin2PI_5 = sin(2.0 * PI / 5.0);
  private static final double sin2PI_7 = sin(2.0 * PI / 7.0);
  private static final double sin4PI_7 = sin(4.0 * PI / 7.0);
  private static final double sin6PI_7 = sin(6.0 * PI / 7.0);
  private static final double cos2PI_7 = cos(2.0 * PI / 7.0);
  private static final double cos4PI_7 = cos(4.0 * PI / 7.0);
  private static final double cos6PI_7 = cos(6.0 * PI / 7.0);
  /**
   * Number of complex numbers in the transform.
   */
  private final int n;
  /**
   * Factorization of n.
   */
  private final int[] factors;
  /**
   * Twiddle factors.
   */
  private final double[][][] twiddle;
  /**
   * Packing of non-contiguous data.
   */
  private final double[] packedData;
  /**
   * Scratch space for the transform.
   */
  private final double[] scratch;

  /**
   * Sign of negative -1 is for forward transform. Sign of 1 is for inverse transform.
   */
  private int sign = -1;

  /**
   * Construct a Complex instance for data of length n. Factorization of n is designed to use special
   * methods for small factors, and a general routine for large odd prime factors. Scratch memory is
   * created of length 2*n, which is reused each time a transform is computed.
   *
   * @param n Number of complex numbers (n .GT. 1).
   */
  public Complex(int n) {
    assert (n > 1);

    this.n = n;
    factors = factor();
    twiddle = wavetable();
    packedData = new double[2 * n];
    scratch = new double[2 * n];
  }

  /**
   * Check if a dimension is a preferred dimension.
   *
   * @param dim the dimension to check.
   * @return true if the dimension is a preferred dimension.
   */
  public static boolean preferredDimension(int dim) {
    if (dim < 2) {
      return false;
    }

    // Apply preferred factors.
    for (int factor : availableFactors) {
      while ((dim % factor) == 0) {
        dim /= factor;
      }
    }
    return dim <= 1;
  }

  /**
   * Getter for the field <code>factors</code>.
   *
   * @return an array of int.
   */
  public int[] getFactors() {
    return factors;
  }

  /**
   * Compute the Fast Fourier Transform of data leaving the result in data. The array data must
   * contain the data points in the following locations:
   *
   * <PRE>
   * Re(d[i]) = data[offset + stride*i] Im(d[i]) = data[offset + stride*i+1]
   * </PRE>
   *
   * @param data an array of double.
   * @param offset the offset to the beginning of the data.
   * @param stride the stride between data points.
   */
  public void fft(double[] data, int offset, int stride) {
    transformInternal(data, offset, stride, -1);
  }

  /**
   * Compute the (un-normalized) inverse FFT of data, leaving it in place. The frequency domain data
   * must be in wrap-around order, and be stored in the following locations:
   *
   * <PRE>
   * Re(D[i]) = data[offset + stride*i] Im(D[i]) = data[offset + stride*i+1]
   * </PRE>
   *
   * @param data an array of double.
   * @param offset the offset to the beginning of the data.
   * @param stride the stride between data points.
   */
  public void ifft(double[] data, int offset, int stride) {
    transformInternal(data, offset, stride, +1);
  }

  /**
   * Compute the normalized inverse FFT of data, leaving it in place. The frequency domain data must
   * be stored in the following locations:
   *
   * <PRE>
   * Re(D[i]) = data[offset + stride*i] Im(D[i]) = data[offset + stride*i+1]
   * </PRE>
   *
   * @param data an array of double.
   * @param offset the offset to the beginning of the data.
   * @param stride the stride between data points.
   */
  public void inverse(double[] data, int offset, int stride) {
    ifft(data, offset, stride);

    // Normalize inverse FFT with 1/n.
    double norm = normalization();
    for (int i = 0; i < n; i++) {
      final int index = offset + stride * i;
      data[index] *= norm;
      data[index + 1] *= norm;
    }
  }

  /**
   * Factor the data length into preferred factors (those with special methods), falling back to odd
   * primes that the general routine must handle.
   *
   * @return integer factors
   */
  private int[] factor() {
    if (n < 2) {
      return null;
    }
    Vector<Integer> v = new Vector<>();
    int nTest = n;

    // Use the preferred factors first
    for (int factor : availableFactors) {
      while ((nTest % factor) == 0) {
        nTest /= factor;
        v.add(factor);
      }
    }

    // Unavailable odd prime factors.
    int factor = firstUnavailablePrime;
    while (nTest > 1) {
      while ((nTest % factor) != 0) {
        factor += 2;
      }
      nTest /= factor;
      v.add(factor);
    }
    int product = 1;
    int nf = v.size();
    int[] ret = new int[nf];
    for (int i = 0; i < nf; i++) {
      ret[i] = v.get(i);
      product *= ret[i];
    }

    // Report a failed factorization.
    if (product != n) {
      StringBuilder sb = new StringBuilder(" FFT factorization failed for " + n + "\n");
      for (int i = 0; i < nf; i++) {
        sb.append(" ");
        sb.append(ret[i]);
      }
      sb.append("\n");
      sb.append(" Factor product = ");
      sb.append(product);
      sb.append("\n");
      logger.severe(sb.toString());
      System.exit(-1);
    } else {
      if (logger.isLoggable(Level.FINEST)) {
        StringBuilder sb = new StringBuilder(" FFT factorization for " + n + " = ");
        for (int i = 0; i < nf - 1; i++) {
          sb.append(ret[i]);
          sb.append(" * ");
        }
        sb.append(ret[nf - 1]);
        logger.fine(sb.toString());
      }
    }
    return ret;
  }

  /**
   * References to the input and output data arrays.
   *
   * @param in Input data for the current pass.
   * @param inOffset Offset into the input data.
   * @param out Output data for the current pass.
   * @param outOffset Offset into output array.
   */
  private record PassData(double[] in, int inOffset, double[] out, int outOffset) {
    // Empty.
  }

  /**
   * Compute the Fast Fourier Transform of data leaving the result in data.
   *
   * @param data data an array of double.
   * @param offset the offset to the beginning of the data.
   * @param stride the stride between data points.
   * @param sign the sign to apply.
   */
  private void transformInternal(final double[] data,
      final int offset, final int stride, final int sign) {

    PassData[] passData;
    boolean packed = false;
    if (stride != 2) {
      // Pack non-contiguous (stride > 2) data into a contiguous array.
      packed = true;
      for (int i = 0, i2 = 0, index = offset; i < n; i++, i2 += 2, index += stride) {
        packedData[i2] = data[index];
        packedData[i2 + 1] = data[index + 1];
      }
      PassData evenPass = new PassData(packedData, 0, scratch, 0);
      PassData oddPass = new PassData(scratch, 0, packedData, 0);
      passData = new PassData[] {evenPass, oddPass};
    } else {
      PassData evenPass = new PassData(data, offset, scratch, 0);
      PassData oddPass = new PassData(scratch, 0, data, offset);
      passData = new PassData[] {evenPass, oddPass};
    }

    this.sign = sign;
    int product = 1;

    final int nfactors = factors.length;
    for (int i = 0; i < nfactors; i++) {
      final int pass = i % 2;
      final int factor = factors[i];
      product *= factor;
      switch (factor) {
        case 2 -> pass2(product, passData[pass], twiddle[i]);
        case 3 -> pass3(product, passData[pass], twiddle[i]);
        case 4 -> pass4(product, passData[pass], twiddle[i]);
        case 5 -> pass5(product, passData[pass], twiddle[i]);
        case 6 -> pass6(product, passData[pass], twiddle[i]);
        case 7 -> pass7(product, passData[pass], twiddle[i]);
        default -> passOdd(factor, product, passData[pass], twiddle[i]);
      }
    }

    // If the number of factors is odd, the final result is in the scratch array.
    if (nfactors % 2 == 1) {
      // Copy the scratch array to the data array.
      if (stride == 2) {
        arraycopy(scratch, 0, data, offset, 2 * n);
      } else {
        for (int i = 0, i2 = 0, index = offset; i < n; i++, i2 += 2, index += stride) {
          data[index] = scratch[i2];
          data[index + 1] = scratch[i2 + 1];
        }
      }
      // If the number of factors is even, the data may need to be unpacked.
    } else if (packed) {
      for (int i = 0, i2 = 0, index = offset; i < n; i++, i2 += 2, index += stride) {
        data[index] = packedData[i2];
        data[index + 1] = packedData[i2 + 1];
      }
    }
  }

  /**
   * Return the normalization factor. Multiply the elements of the back-transformed data to get the
   * normalized inverse.
   *
   * @return a double.
   */
  private double normalization() {
    return 1.0 / n;
  }

  /**
   * Handle factors of 2.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass2(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 2;
    final int m = n / factor;
    final int q = n / product;
    final int product_1 = product / factor;
    final int di = 2 * m;
    final int dj = 2 * product_1;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += dj) {
      final double[] twids = twiddles[k];
      final double w_r = twids[0];
      final double w_i = -sign * twids[1];
      for (int k1 = 0; k1 < product_1; k1++, i += 2, j += 2) {
        final double z0_r = data[i];
        final double z0_i = data[i + 1];
        final int idi = i + di;
        final double z1_r = data[idi];
        final double z1_i = data[idi + 1];
        ret[j] = z0_r + z1_r;
        ret[j + 1] = z0_i + z1_i;
        final double x_r = z0_r - z1_r;
        final double x_i = z0_i - z1_i;
        final int jdj = j + dj;
        ret[jdj] = fma(w_r, x_r, -w_i * x_i);
        ret[jdj + 1] = fma(w_r, x_i, w_i * x_r);
      }
    }
  }

  /**
   * Handle factors of 3.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass3(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 3;
    final int m = n / factor;
    final int q = n / product;
    final int product_1 = product / factor;
    final double tau = sign * sqrt3_2;
    final int di = 2 * m;
    final int dj = 2 * product_1;
    final int jstep = (factor - 1) * dj;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += jstep) {
      final double[] twids = twiddles[k];
      final double w1_r = twids[0];
      final double w1_i = -sign * twids[1];
      final double w2_r = twids[2];
      final double w2_i = -sign * twids[3];
      for (int k1 = 0; k1 < product_1; k1++, i += 2, j += 2) {
        final double z0_r = data[i];
        final double z0_i = data[i + 1];
        int idi = i + di;
        final double z1_r = data[idi];
        final double z1_i = data[idi + 1];
        idi += di;
        final double z2_r = data[idi];
        final double z2_i = data[idi + 1];
        final double t1_r = z1_r + z2_r;
        final double t1_i = z1_i + z2_i;
        final double t2_r = fma(-0.5, t1_r, z0_r);
        final double t2_i = fma(-0.5, t1_i, z0_i);
        final double t3_r = tau * (z1_r - z2_r);
        final double t3_i = tau * (z1_i - z2_i);
        ret[j] = z0_r + t1_r;
        ret[j + 1] = z0_i + t1_i;
        double x_r = t2_r - t3_i;
        double x_i = t2_i + t3_r;
        int jdj = j + dj;
        ret[jdj] = fma(w1_r, x_r, -w1_i * x_i);
        ret[jdj + 1] = fma(w1_r, x_i, w1_i * x_r);
        x_r = t2_r + t3_i;
        x_i = t2_i - t3_r;
        jdj += dj;
        ret[jdj] = fma(w2_r, x_r, -w2_i * x_i);
        ret[jdj + 1] = fma(w2_r, x_i, w2_i * x_r);
      }
    }
  }

  /**
   * Handle factors of 4.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass4(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 4;
    final int m = n / factor;
    final int q = n / product;
    final int p_1 = product / factor;
    final int di = 2 * m;
    final int dj = 2 * p_1;
    final int jstep = (factor - 1) * dj;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += jstep) {
      final double[] twids = twiddles[k];
      final double w1_r = twids[0];
      final double w1_i = -sign * twids[1];
      final double w2_r = twids[2];
      final double w2_i = -sign * twids[3];
      final double w3_r = twids[4];
      final double w3_i = -sign * twids[5];
      for (int k1 = 0; k1 < p_1; k1++, i += 2, j += 2) {
        final double z0_r = data[i];
        final double z0_i = data[i + 1];
        int idi = i + di;
        final double z1_r = data[idi];
        final double z1_i = data[idi + 1];
        idi += di;
        final double z2_r = data[idi];
        final double z2_i = data[idi + 1];
        idi += di;
        final double z3_r = data[idi];
        final double z3_i = data[idi + 1];
        final double t1_r = z0_r + z2_r;
        final double t1_i = z0_i + z2_i;
        final double t2_r = z1_r + z3_r;
        final double t2_i = z1_i + z3_i;
        final double t3_r = z0_r - z2_r;
        final double t3_i = z0_i - z2_i;
        final double t4_r = sign * (z1_r - z3_r);
        final double t4_i = sign * (z1_i - z3_i);
        ret[j] = t1_r + t2_r;
        ret[j + 1] = t1_i + t2_i;
        double x_r = t3_r - t4_i;
        double x_i = t3_i + t4_r;
        int jdj = j + dj;
        ret[jdj] = fma(w1_r, x_r, -w1_i * x_i);
        ret[jdj + 1] = fma(w1_r, x_i, w1_i * x_r);
        x_r = t1_r - t2_r;
        x_i = t1_i - t2_i;
        jdj += dj;
        ret[jdj] = fma(w2_r, x_r, -w2_i * x_i);
        ret[jdj + 1] = fma(w2_r, x_i, w2_i * x_r);
        x_r = t3_r + t4_i;
        x_i = t3_i - t4_r;
        jdj += dj;
        ret[jdj] = fma(w3_r, x_r, -w3_i * x_i);
        ret[jdj + 1] = fma(w3_r, x_i, w3_i * x_r);
      }
    }
  }

  /**
   * Handle factors of 5.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass5(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 5;
    final int m = n / factor;
    final int q = n / product;
    final int p_1 = product / factor;
    final double tau = sqrt5_4;
    final double sin2PI_5s = sign * sin2PI_5;
    final double sinPI_5s = sign * sinPI_5;
    final int di = 2 * m;
    final int dj = 2 * p_1;
    final int jstep = (factor - 1) * dj;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += jstep) {
      final double[] twids = twiddles[k];
      final double w1r = twids[0];
      final double w1i = -sign * twids[1];
      final double w2r = twids[2];
      final double w2i = -sign * twids[3];
      final double w3r = twids[4];
      final double w3i = -sign * twids[5];
      final double w4r = twids[6];
      final double w4i = -sign * twids[7];
      for (int k1 = 0; k1 < p_1; k1++, i += 2, j += 2) {
        final double z0r = data[i];
        final double z0i = data[i + 1];
        int idi = i + di;
        final double z1r = data[idi];
        final double z1i = data[idi + 1];
        idi += di;
        final double z2r = data[idi];
        final double z2i = data[idi + 1];
        idi += di;
        final double z3r = data[idi];
        final double z3i = data[idi + 1];
        idi += di;
        final double z4r = data[idi];
        final double z4i = data[idi + 1];
        final double t1r = z1r + z4r;
        final double t1i = z1i + z4i;
        final double t2r = z2r + z3r;
        final double t2i = z2i + z3i;
        final double t3r = z1r - z4r;
        final double t3i = z1i - z4i;
        final double t4r = z2r - z3r;
        final double t4i = z2i - z3i;
        final double t5r = t1r + t2r;
        final double t5i = t1i + t2i;
        final double t6r = tau * (t1r - t2r);
        final double t6i = tau * (t1i - t2i);
        final double t7r = fma(-0.25, t5r, z0r);
        final double t7i = fma(-0.25, t5i, z0i);
        final double t8r = t7r + t6r;
        final double t8i = t7i + t6i;
        final double t9r = t7r - t6r;
        final double t9i = t7i - t6i;
        final double t10r = fma(sin2PI_5s, t3r, sinPI_5s * t4r);
        final double t10i = fma(sin2PI_5s, t3i, sinPI_5s * t4i);
        final double t11r = fma(-sin2PI_5s, t4r, sinPI_5s * t3r);
        final double t11i = fma(-sin2PI_5s, t4i, sinPI_5s * t3i);
        ret[j] = z0r + t5r;
        ret[j + 1] = z0i + t5i;
        double xr = t8r - t10i;
        double xi = t8i + t10r;
        int jdj = j + dj;
        ret[jdj] = fma(w1r, xr, -w1i * xi);
        ret[jdj + 1] = fma(w1r, xi, w1i * xr);
        xr = t9r - t11i;
        xi = t9i + t11r;
        jdj += dj;
        ret[jdj] = fma(w2r, xr, -w2i * xi);
        ret[jdj + 1] = fma(w2r, xi, w2i * xr);
        xr = t9r + t11i;
        xi = t9i - t11r;
        jdj += dj;
        ret[jdj] = fma(w3r, xr, -w3i * xi);
        ret[jdj + 1] = fma(w3r, xi, w3i * xr);
        xr = t8r + t10i;
        xi = t8i - t10r;
        jdj += dj;
        ret[jdj] = fma(w4r, xr, -w4i * xi);
        ret[jdj + 1] = fma(w4r, xi, w4i * xr);
      }
    }
  }

  /**
   * Handle factors of 6.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass6(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 6;
    final int m = n / factor;
    final int q = n / product;
    final int p_1 = product / factor;
    final double tau = sign * sqrt3_2;
    final int di = 2 * m;
    final int dj = 2 * p_1;
    final int jstep = (factor - 1) * dj;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += jstep) {
      final double[] twids = twiddles[k];
      final double w1r = twids[0];
      final double w1i = -sign * twids[1];
      final double w2r = twids[2];
      final double w2i = -sign * twids[3];
      final double w3r = twids[4];
      final double w3i = -sign * twids[5];
      final double w4r = twids[6];
      final double w4i = -sign * twids[7];
      final double w5r = twids[8];
      final double w5i = -sign * twids[9];
      for (int k1 = 0; k1 < p_1; k1++, i += 2, j += 2) {
        final double z0r = data[i];
        final double z0i = data[i + 1];
        int idi = i + di;
        final double z1r = data[idi];
        final double z1i = data[idi + 1];
        idi += di;
        final double z2r = data[idi];
        final double z2i = data[idi + 1];
        idi += di;
        final double z3r = data[idi];
        final double z3i = data[idi + 1];
        idi += di;
        final double z4r = data[idi];
        final double z4i = data[idi + 1];
        idi += di;
        final double z5r = data[idi];
        final double z5i = data[idi + 1];
        final double ta1r = z2r + z4r;
        final double ta1i = z2i + z4i;
        final double ta2r = fma(-0.5, ta1r, z0r);
        final double ta2i = fma(-0.5, ta1i, z0i);
        final double ta3r = tau * (z2r - z4r);
        final double ta3i = tau * (z2i - z4i);
        final double a0r = z0r + ta1r;
        final double a0i = z0i + ta1i;
        final double a1r = ta2r - ta3i;
        final double a1i = ta2i + ta3r;
        final double a2r = ta2r + ta3i;
        final double a2i = ta2i - ta3r;
        final double tb1r = z5r + z1r;
        final double tb1i = z5i + z1i;
        final double tb2r = fma(-0.5, tb1r, z3r);
        final double tb2i = fma(-0.5, tb1i, z3i);
        final double tb3r = tau * (z5r - z1r);
        final double tb3i = tau * (z5i - z1i);
        final double b0r = z3r + tb1r;
        final double b0i = z3i + tb1i;
        final double b1r = tb2r - tb3i;
        final double b1i = tb2i + tb3r;
        final double b2r = tb2r + tb3i;
        final double b2i = tb2i - tb3r;
        ret[j] = a0r + b0r;
        ret[j + 1] = a0i + b0i;
        double xr = a1r - b1r;
        double xi = a1i - b1i;
        int jdj = j + dj;
        ret[jdj] = fma(w1r, xr, -w1i * xi);
        ret[jdj + 1] = fma(w1r, xi, w1i * xr);
        xr = a2r + b2r;
        xi = a2i + b2i;
        jdj += dj;
        ret[jdj] = fma(w2r, xr, -w2i * xi);
        ret[jdj + 1] = fma(w2r, xi, w2i * xr);
        xr = a0r - b0r;
        xi = a0i - b0i;
        jdj += dj;
        ret[jdj] = fma(w3r, xr, -w3i * xi);
        ret[jdj + 1] = fma(w3r, xi, w3i * xr);
        xr = a1r + b1r;
        xi = a1i + b1i;
        jdj += dj;
        ret[jdj] = fma(w4r, xr, -w4i * xi);
        ret[jdj + 1] = fma(w4r, xi, w4i * xr);
        xr = a2r - b2r;
        xi = a2i - b2i;
        jdj += dj;
        ret[jdj] = fma(w5r, xr, -w5i * xi);
        ret[jdj + 1] = fma(w5r, xi, w5i * xr);
      }
    }
  }

  /**
   * Handle factors of 7.
   *
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void pass7(int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final double[] ret = passData.out;
    final int factor = 7;
    final int m = n / factor;
    final int q = n / product;
    final int p_1 = product / factor;
    final double c1 = cos2PI_7;
    final double c2 = cos4PI_7;
    final double c3 = cos6PI_7;
    final double s1 = (-sign) * sin2PI_7;
    final double s2 = (-sign) * sin4PI_7;
    final double s3 = (-sign) * sin6PI_7;
    final int di = 2 * m;
    final int dj = 2 * p_1;
    final int jstep = (factor - 1) * dj;
    int i = passData.inOffset;
    int j = passData.outOffset;
    for (int k = 0; k < q; k++, j += jstep) {
      final double[] twids = twiddles[k];
      final double w1r = twids[0];
      final double w1i = -sign * twids[1];
      final double w2r = twids[2];
      final double w2i = -sign * twids[3];
      final double w3r = twids[4];
      final double w3i = -sign * twids[5];
      final double w4r = twids[6];
      final double w4i = -sign * twids[7];
      final double w5r = twids[8];
      final double w5i = -sign * twids[9];
      final double w6r = twids[10];
      final double w6i = -sign * twids[11];
      for (int k1 = 0; k1 < p_1; k1++, i += 2, j += 2) {
        final double z0r = data[i];
        final double z0i = data[i + 1];
        int idi = i + di;
        final double z1r = data[idi];
        final double z1i = data[idi + 1];
        idi += di;
        final double z2r = data[idi];
        final double z2i = data[idi + 1];
        idi += di;
        final double z3r = data[idi];
        final double z3i = data[idi + 1];
        idi += di;
        final double z4r = data[idi];
        final double z4i = data[idi + 1];
        idi += di;
        final double z5r = data[idi];
        final double z5i = data[idi + 1];
        idi += di;
        final double z6r = data[idi];
        final double z6i = data[idi + 1];
        final double t0r = z1r + z6r;
        final double t0i = z1i + z6i;
        final double t1r = z1r - z6r;
        final double t1i = z1i - z6i;
        final double t2r = z2r + z5r;
        final double t2i = z2i + z5i;
        final double t3r = z2r - z5r;
        final double t3i = z2i - z5i;
        final double t4r = z4r + z3r;
        final double t4i = z4i + z3i;
        final double t5r = z4r - z3r;
        final double t5i = z4i - z3i;
        final double t6r = t2r + t0r;
        final double t6i = t2i + t0i;
        final double t7r = t5r + t3r;
        final double t7i = t5i + t3i;
        final double b0r = z0r + t6r + t4r;
        final double b0i = z0i + t6i + t4i;
        final double v1 = (c1 + c2 + c3) * oneThird - 1.0;
        final double v2 = (2.0 * c1 - c2 - c3) * oneThird;
        final double v3 = (c1 - 2.0 * c2 + c3) * oneThird;
        final double v4 = (c1 + c2 - 2.0 * c3) * oneThird;
        final double v5 = (s1 + s2 - s3) * oneThird;
        final double v6 = (2.0 * s1 - s2 + s3) * oneThird;
        final double v7 = (s1 - 2.0 * s2 - s3) * oneThird;
        final double v8 = (s1 + s2 + 2.0 * s3) * oneThird;
        final double b1r = v1 * (t6r + t4r);
        final double b1i = v1 * (t6i + t4i);
        final double b2r = v2 * (t0r - t4r);
        final double b2i = v2 * (t0i - t4i);
        final double b3r = v3 * (t4r - t2r);
        final double b3i = v3 * (t4i - t2i);
        final double b4r = v4 * (t2r - t0r);
        final double b4i = v4 * (t2i - t0i);
        final double b5r = v5 * (t7r + t1r);
        final double b5i = v5 * (t7i + t1i);
        final double b6r = v6 * (t1r - t5r);
        final double b6i = v6 * (t1i - t5i);
        final double b7r = v7 * (t5r - t3r);
        final double b7i = v7 * (t5i - t3i);
        final double b8r = v8 * (t3r - t1r);
        final double b8i = v8 * (t3i - t1i);
        final double u0r = b0r + b1r;
        final double u0i = b0i + b1i;
        final double u1r = b2r + b3r;
        final double u1i = b2i + b3i;
        final double u2r = b4r - b3r;
        final double u2i = b4i - b3i;
        final double u3r = -b2r - b4r;
        final double u3i = -b2i - b4i;
        final double u4r = b6r + b7r;
        final double u4i = b6i + b7i;
        final double u5r = b8r - b7r;
        final double u5i = b8i - b7i;
        final double u6r = -b8r - b6r;
        final double u6i = -b8i - b6i;
        final double u7r = u0r + u1r;
        final double u7i = u0i + u1i;
        final double u8r = u0r + u2r;
        final double u8i = u0i + u2i;
        final double u9r = u0r + u3r;
        final double u9i = u0i + u3i;
        final double u10r = u4r + b5r;
        final double u10i = u4i + b5i;
        final double u11r = u5r + b5r;
        final double u11i = u5i + b5i;
        final double u12r = u6r + b5r;
        final double u12i = u6i + b5i;
        ret[j] = b0r;
        ret[j + 1] = b0i;
        double xr = u7r + u10i;
        double xi = u7i - u10r;
        int jdj = j + dj;
        ret[jdj] = fma(w1r, xr, -w1i * xi);
        ret[jdj + 1] = fma(w1r, xi, w1i * xr);
        xr = u9r + u12i;
        xi = u9i - u12r;
        jdj += dj;
        ret[jdj] = fma(w2r, xr, -w2i * xi);
        ret[jdj + 1] = fma(w2r, xi, w2i * xr);
        xr = u8r - u11i;
        xi = u8i + u11r;
        jdj += dj;
        ret[jdj] = fma(w3r, xr, -w3i * xi);
        ret[jdj + 1] = fma(w3r, xi, w3i * xr);
        xr = u8r + u11i;
        xi = u8i - u11r;
        jdj += dj;
        ret[jdj] = fma(w4r, xr, -w4i * xi);
        ret[jdj + 1] = fma(w4r, xi, w4i * xr);
        xr = u9r - u12i;
        xi = u9i + u12r;
        jdj += dj;
        ret[jdj] = fma(w5r, xr, -w5i * xi);
        ret[jdj + 1] = fma(w5r, xi, w5i * xr);
        xr = u7r - u10i;
        xi = u7i + u10r;
        jdj += dj;
        ret[jdj] = fma(w6r, xr, -w6i * xi);
        ret[jdj + 1] = fma(w6r, xi, w6i * xr);
      }
    }
  }

  /**
   * Note that passOdd is only intended for odd factors (and fails for even factors).
   *
   * @param factor Factor to apply.
   * @param product Product to apply.
   * @param passData the data.
   * @param twiddles the twiddle factors.
   */
  private void passOdd(int factor, int product, PassData passData, double[][] twiddles) {
    final double[] data = passData.in;
    final int dataOffset = passData.inOffset;
    final double[] ret = passData.out;
    final int retOffset = passData.outOffset;

    final int m = n / factor;
    final int q = n / product;
    final int p_1 = product / factor;
    final int jump = (factor - 1) * p_1;
    for (int i = 0; i < m; i++) {
      ret[retOffset + 2 * i] = data[dataOffset + 2 * i];
      ret[retOffset + 2 * i + 1] = data[dataOffset + 2 * i + 1];
    }
    for (int e = 1; e < (factor - 1) / 2 + 1; e++) {
      for (int i = 0; i < m; i++) {
        int idx = i + e * m;
        int idxc = i + (factor - e) * m;
        ret[retOffset + 2 * idx] = data[dataOffset + 2 * idx] + data[dataOffset + 2 * idxc];
        ret[retOffset + 2 * idx + 1] =
            data[dataOffset + 2 * idx + 1] + data[dataOffset + 2 * idxc + 1];
        ret[retOffset + 2 * idxc] = data[dataOffset + 2 * idx] - data[dataOffset + 2 * idxc];
        ret[retOffset + 2 * idxc + 1] =
            data[dataOffset + 2 * idx + 1] - data[dataOffset + 2 * idxc + 1];
      }
    }
    for (int i = 0; i < m; i++) {
      data[dataOffset + 2 * i] = ret[retOffset + 2 * i];
      data[dataOffset + 2 * i + 1] = ret[retOffset + 2 * i + 1];
    }
    for (int e1 = 1; e1 < (factor - 1) / 2 + 1; e1++) {
      for (int i = 0; i < m; i++) {
        int i1 = retOffset + 2 * (i + e1 * m);
        data[dataOffset + 2 * i] += ret[i1];
        data[dataOffset + 2 * i + 1] += ret[i1 + 1];
      }
    }
    double[] twiddl = twiddles[q];
    for (int e = 1; e < (factor - 1) / 2 + 1; e++) {
      int idx = e;
      double wr, wi;
      int em = e * m;
      int ecm = (factor - e) * m;
      for (int i = 0; i < m; i++) {
        data[dataOffset + 2 * (i + em)] = ret[retOffset + 2 * i];
        data[dataOffset + 2 * (i + em) + 1] = ret[retOffset + 2 * i + 1];
        data[dataOffset + 2 * (i + ecm)] = ret[retOffset + 2 * i];
        data[dataOffset + 2 * (i + ecm) + 1] = ret[retOffset + 2 * i + 1];
      }
      for (int e1 = 1; e1 < (factor - 1) / 2 + 1; e1++) {
        if (idx == 0) {
          wr = 1;
          wi = 0;
        } else {
          wr = twiddl[2 * (idx - 1)];
          wi = -sign * twiddl[2 * (idx - 1) + 1];
        }
        for (int i = 0; i < m; i++) {
          int i1 = retOffset + 2 * (i + e1 * m);
          int i2 = retOffset + 2 * (i + (factor - e1) * m);
          double ap = wr * ret[i1];
          double am = wi * ret[i2 + 1];
          double bp = wr * ret[i1 + 1];
          double bm = wi * ret[i2];
          data[dataOffset + 2 * (i + em)] += (ap - am);
          data[dataOffset + 2 * (i + em) + 1] += (bp + bm);
          data[dataOffset + 2 * (i + ecm)] += (ap + am);
          data[dataOffset + 2 * (i + ecm) + 1] += (bp - bm);
        }
        idx += e;
        idx %= factor;
      }
    }
    /* k = 0 */
    for (int k1 = 0; k1 < p_1; k1++) {
      ret[retOffset + 2 * k1] = data[dataOffset + 2 * k1];
      ret[retOffset + 2 * k1 + 1] = data[dataOffset + 2 * k1 + 1];
    }
    for (int e1 = 1; e1 < factor; e1++) {
      for (int k1 = 0; k1 < p_1; k1++) {
        int i = retOffset + 2 * (k1 + e1 * p_1);
        int i1 = dataOffset + 2 * (k1 + e1 * m);
        ret[i] = data[i1];
        ret[i + 1] = data[i1 + 1];
      }
    }
    int i = p_1;
    int j = product;
    for (int k = 1; k < q; k++) {
      for (int k1 = 0; k1 < p_1; k1++) {
        ret[retOffset + 2 * j] = data[dataOffset + 2 * i];
        ret[retOffset + 2 * j + 1] = data[dataOffset + 2 * i + 1];
        i++;
        j++;
      }
      j += jump;
    }
    i = p_1;
    j = product;
    for (int k = 1; k < q; k++) {
      twiddl = twiddles[k];
      for (int k1 = 0; k1 < p_1; k1++) {
        for (int e1 = 1; e1 < factor; e1++) {
          int i1 = dataOffset + 2 * (i + e1 * m);
          double xr = data[i1];
          double xi = data[i1 + 1];
          double wr = twiddl[2 * (e1 - 1)];
          double wi = -sign * twiddl[2 * (e1 - 1) + 1];
          int i2 = retOffset + 2 * (j + e1 * p_1);
          ret[i2] = fma(wr, xr, -wi * xi);
          ret[i2 + 1] = fma(wr, xi, wi * xr);
        }
        i++;
        j++;
      }
      j += jump;
    }
  }

  /**
   * Compute twiddle factors. These are trigonometric constants that depend on the factoring of n.
   *
   * @return twiddle factors.
   */
  private double[][][] wavetable() {
    if (n < 2) {
      return null;
    }
    final double d_theta = -2.0 * PI / n;
    final double[][][] ret = new double[factors.length][][];
    int product = 1;
    for (int i = 0; i < factors.length; i++) {
      int factor = factors[i];
      int product_1 = product;
      product *= factor;
      final int q = n / product;
      ret[i] = new double[q + 1][2 * (factor - 1)];
      final double[][] twid = ret[i];
      for (int j = 0; j < factor - 1; j++) {
        twid[0][2 * j] = 1.0;
        twid[0][2 * j + 1] = 0.0;
      }
      for (int k = 1; k <= q; k++) {
        int m = 0;
        for (int j = 0; j < factor - 1; j++) {
          m += k * product_1;
          m %= n;
          final double theta = d_theta * m;
          twid[k][2 * j] = cos(theta);
          twid[k][2 * j + 1] = sin(theta);
        }
      }
    }
    return ret;
  }

  /**
   * Static DFT method used to test the FFT.
   *
   * @param in input array.
   * @param out output array.
   */
  public static void dft(double[] in, double[] out) {
    int n = in.length / 2;
    for (int k = 0; k < n; k++) { // For each output element
      double sumReal = 0;
      double simImag = 0;
      for (int t = 0; t < n; t++) { // For each input element
        double angle = (2 * PI * t * k) / n;
        int re = 2 * t;
        int im = 2 * t + 1;
        sumReal = fma(in[re], cos(angle), sumReal);
        sumReal = fma(in[im], sin(angle), sumReal);
        simImag = fma(-in[re], sin(angle), simImag);
        simImag = fma(in[im], cos(angle), simImag);
      }
      int re = 2 * k;
      int im = 2 * k + 1;
      out[re] = sumReal;
      out[im] = simImag;
    }
  }
}
