/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package org.twentyn.proteintodna;

public class ProteinInformation {

  public String getOrganism() {
    return organism;
  }

  public void setOrganism(String organism) {
    this.organism = organism;
  }

  public String getEcnum() {
    return ecnum;
  }

  public void setEcnum(String ecnum) {
    this.ecnum = ecnum;
  }

  public String getProteinDesc() { return proteinDesc; }

  public void setProteinDesc(String proteinDesc) { this.proteinDesc = proteinDesc; }

  public String getProteinSeq() { return proteinSeq; }

  public void setProteinSeq(String proteinSeq) { this.proteinSeq = proteinSeq; }

  private String organism;
  private String ecnum;
  private String proteinSeq;
  private String proteinDesc;

  public ProteinInformation(String organism, String ecnum, String proteinSeq, String proteinDesc) {
    this.organism = organism;
    this.ecnum = ecnum;
    this.proteinDesc = proteinDesc;
    this.proteinSeq = proteinSeq;
  }

  private ProteinInformation() {}

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ProteinInformation that = (ProteinInformation) o;

    if (organism != null ? !organism.equals(that.organism) : that.organism != null) return false;
    if (ecnum != null ? !ecnum.equals(that.ecnum) : that.ecnum != null) return false;
    if (proteinSeq != null ? !proteinSeq.equals(that.proteinSeq) : that.proteinSeq != null) return false;
    return proteinDesc != null ? proteinDesc.equals(that.proteinDesc) : that.proteinDesc == null;

  }

  @Override
  public int hashCode() {
    int result = organism != null ? organism.hashCode() : 0;
    result = 31 * result + (ecnum != null ? ecnum.hashCode() : 0);
    result = 31 * result + (proteinSeq != null ? proteinSeq.hashCode() : 0);
    result = 31 * result + (proteinDesc != null ? proteinDesc.hashCode() : 0);
    return result;
  }
}
