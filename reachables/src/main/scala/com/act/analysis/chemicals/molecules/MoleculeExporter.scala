package com.act.analysis.chemicals.molecules

import chemaxon.formats.MolExporter
import chemaxon.marvin.io.MolExportException
import chemaxon.struc.Molecule

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

object MoleculeExporter {
  private val moleculeCache = TrieMap[MoleculeFormat.Value, TrieMap[Molecule, String]]()
  private var defaultFormat = List(MoleculeFormat.inchi)

  def setDefaultFormat(format: MoleculeFormat.Value): Unit = {
    setDefaultFormat(List(format))
  }

  def setDefaultFormat(formats: List[MoleculeFormat.Value]): Unit = {
    defaultFormat = formats
  }

  @throws[MolExportException]
  def exportAsSmarts(mol: Molecule): String = {
    exportMolecule(mol, MoleculeFormat.smarts)
  }

  @throws[MolExportException]
  def exportAsInchi(mol: Molecule): String = {
    exportMolecule(mol, MoleculeFormat.stdInchi)
  }

  @throws[MolExportException]
  def exportMoleculesAsFormats(mols: List[Molecule], formats: List[MoleculeFormat.Value]): List[String] = {
    mols.map(exportMoleculeAsFormats(_, formats))
  }

  @throws[MolExportException]
  def exportMoleculesDefaultFormat(mols: List[Molecule]): List[String] = {
    mols.map(exportMoleculeDefaultFormat)
  }

  @throws[MolExportException]
  def exportMoleculesAsFormatsJava(mols: List[Molecule], formats: List[MoleculeFormat.Value]): java.util.List[String] = {
    mols.map(exportMoleculeAsFormats(_, formats)).asJava
  }

  @throws[MolExportException]
  def exportMoleculeAsFormats(mol: Molecule, formats: List[MoleculeFormat.Value]): String = {
    formats.foreach(format => {
      try {
        return exportMolecule(mol, format)
      } catch {
        case e: MolExportException => None
      }
    })

    throw new MolExportException("Could not convert molecules into any valid formats.")
  }

  def exportMolecule(mol: Molecule, format: MoleculeFormat.Value): String = {
    val formatCache = moleculeCache.get(format)

    if (formatCache.isEmpty) {
      moleculeCache.put(format, new TrieMap[Molecule, String])
    }

    val smartsFormat = moleculeCache(format).get(mol)

    if (smartsFormat.isEmpty) {
      val newFormat = MolExporter.exportToFormat(mol, MoleculeFormat.getExportString(format))
      moleculeCache(format).put(mol, newFormat)
      return newFormat
    }

    smartsFormat.get
  }

  @throws[MolExportException]
  def exportMoleculesDefaultFormatJava(mols: List[Molecule]): java.util.List[String] = {
    mols.map(exportMoleculeDefaultFormat).asJava
  }

  @throws[MolExportException]
  def exportMoleculeDefaultFormat(mol: Molecule): String = {
    exportMoleculeAsFormats(mol, defaultFormat)
  }

}

