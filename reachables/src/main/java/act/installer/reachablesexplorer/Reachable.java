package act.installer.reachablesexplorer;


import act.shared.Chemical;
import chemaxon.formats.MolExporter;
import chemaxon.formats.MolFormatException;
import chemaxon.struc.Molecule;
import com.act.analysis.chemicals.molecules.MoleculeExporter;
import com.act.analysis.chemicals.molecules.MoleculeFormat;
import com.act.analysis.chemicals.molecules.MoleculeImporter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.mongojack.ObjectId;


import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class Reachable {



  public Reachable() {}

  public Reachable(String pageName, String inchi, String smiles, String inchikey, String structureFilename, List<String> names, String wordCloudFilename) {
    this.pageName = pageName;
    this.inchi = inchi;
    this.smiles = smiles;
    this.inchikey = inchikey;
    this.structureFilename = structureFilename;
    this.names = names;
    this.wordCloudFilename = wordCloudFilename;
    this.precursorData = new PrecursorData();
  }

  @JsonCreator
  public Reachable(@JsonProperty("page_name") String pageName,
                   @JsonProperty("inchi") String inchi,
                   @JsonProperty("smiles") String smiles,
                   @JsonProperty("rendering-filename") String structureFilename,
                   @JsonProperty("names") List<String> names,
                   @JsonProperty("usage-wordcloud-filename") String wordCloudFilename,
                   @JsonProperty("precursor") PrecursorData precursors) {
    this.pageName = pageName;
    this.inchi = inchi;
    this.smiles = smiles;
    this.structureFilename = structureFilename;
    this.names = names;
    this.wordCloudFilename = wordCloudFilename;
    this.precursorData = precursors;
  }


  public void setPrecursorData(PrecursorData precursorData) {
    this.precursorData = precursorData;
  }

  public PrecursorData getPrecursorData() {
    return this.precursorData;
  }

  private String id;
  @ObjectId
  @JsonProperty("_id")
  public String getId() {
    return id;
  }
  @ObjectId
  @JsonProperty("_id")
  public void setId(String id) {
    this.id = id;
  }

  @JsonProperty("page_name")
  private String pageName;

  @JsonProperty("inchi")
  private String inchi;

  public String getInchi(){
    return inchi;
  }

  @JsonProperty("smiles")
  private String smiles;

  @JsonProperty("inchikey")
  private String inchikey;

  @JsonProperty("rendering-filename")
  private String structureFilename;

  @JsonProperty("names")
  private List<String> names;

  @JsonProperty("wikipedia-data")
  private WikipediaData wikipediaData;

  @JsonProperty("usage-wordcloud-filename")
  private String wordCloudFilename;

  @JsonProperty("precursor")
  private PrecursorData precursorData;
  
}