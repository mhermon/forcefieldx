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
package ffx.potential.parsers;

import java.io.File;
import javax.swing.filechooser.FileFilter;
import org.apache.commons.io.FilenameUtils;

/**
 * The ARCFileFilter class is used to choose a TINKER Archive (*.ARC).
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
public final class ARCFileFilter extends FileFilter {

  /** Default Constructor */
  public ARCFileFilter() {
  }

  /**
   * This is a static version of the accept method.
   *
   * <p>This method return <code>true</code> if the file is a directory or TINKER Archive (*.ARC).
   *
   * @param file The File to examine.
   * @return Returns true if this an ARC file.
   */
  public static boolean isARC(File file) {
    return new ARCFileFilter().accept(file);
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method return <code>true</code> if the file is a directory or TINKER Archive (*.ARC).
   */
  @Override
  public boolean accept(File file) {
    if (file.isDirectory()) {
      return true;
    }
    String ext = FilenameUtils.getExtension(file.getName());
    return ext.toUpperCase().startsWith("ARC");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Provides a description of this FileFilter
   */
  @Override
  public String getDescription() {
    return "TINKER Archive (*.ARC)";
  }
}
