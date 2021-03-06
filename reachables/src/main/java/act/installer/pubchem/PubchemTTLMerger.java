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

package act.installer.pubchem;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleIRI;
import org.eclipse.rdf4j.model.impl.SimpleLiteral;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * This class implements a parser for Pubchem's TTL (turtle) files.  These contain both the features available in the
 * full Pubchem compound corpus, as well as other features not available in that dataset.
 */
public class PubchemTTLMerger {
  private static final Logger LOGGER = LogManager.getFormatterLogger(PubchemTTLMerger.class);
  private static final Charset UTF8 = StandardCharsets.UTF_8;
  private static final Set<PC_SYNONYM_TYPES> DEFAULT_SYNONYM_DATA_TYPES =
      Collections.unmodifiableSet(Collections.singleton(PC_SYNONYM_TYPES.UNKNOWN));

  private static final String DEFAULT_ROCKSDB_COLUMN_FAMILY = "default";

  // Dunno why RocksDB needs two different types for these...
  private static final Options ROCKS_DB_CREATE_OPTIONS = new Options()
      .setCreateIfMissing(true)
      .setDisableDataSync(true)
      .setAllowMmapReads(true) // Trying all sorts of performance tweaking knobs, which are not well documented. :(
      .setAllowMmapWrites(true)
      .setWriteBufferSize(1 << 27)
      .setArenaBlockSize(1 << 20)
      .setCompressionType(CompressionType.SNAPPY_COMPRESSION) // Will hopefully trade CPU for I/O.
      ;

  public static final DBOptions ROCKS_DB_OPEN_OPTIONS = new DBOptions()
      .setCreateIfMissing(false)
      .setDisableDataSync(true)
      .setAllowMmapReads(true)
      .setAllowMmapWrites(true)
      ;

  public static final String OPTION_INDEX_PATH = "x";
  public static final String OPTION_RDF_DIRECTORY = "d";
  public static final String OPTION_ONLY_SYNONYMS = "s";
  public static final String OPTION_ONLY_MESH = "m";
  public static final String OPTION_ONLY_PUBCHEM_IDS = "p";
  public static final String OPTION_ONLY_MERGE = "g";
  public static final String OPTION_OPEN_EXISTING_OKAY = "e";

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class extracts Pubchem synonym data from RDF files into an on-disk index, then uses that index to join ",
      "the synonyms and MeSH ids with their corresponding pubchem ids."
  }, "");

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_INDEX_PATH)
        .argName("index path")
        .desc("A path to the directory where the on-disk index will be stored; must not already exist")
        .hasArg().required()
        .longOpt("index")
    );
    add(Option.builder(OPTION_RDF_DIRECTORY)
        .argName("RDF directory")
        .desc("A path to the directory of Pubchem RDF files")
        .hasArg()
        .longOpt("dir")
    );
    add(Option.builder(OPTION_ONLY_SYNONYMS)
        .desc(String.format("If set, only '%s' files will be processed, useful for debugging",
            PC_RDF_DATA_FILE_CONFIG.HASH_TO_SYNONYM.filePrefix))
        .longOpt("only-synonyms")
    );
    add(Option.builder(OPTION_ONLY_MESH)
        .desc(String.format("If set, only '%s' files will be processed, useful for debugging",
            PC_RDF_DATA_FILE_CONFIG.HASH_TO_MESH.filePrefix))
        .longOpt("only-mesh")
    );
    add(Option.builder(OPTION_ONLY_PUBCHEM_IDS)
        .desc(String.format("If set, only '%s' files will be processed, useful for debugging",
            PC_RDF_DATA_FILE_CONFIG.HASH_TO_CID.filePrefix))
        .longOpt("only-pubchem-id")
    );
    add(Option.builder(OPTION_ONLY_MERGE)
        .desc("If set, only merge on Pubchem id, assuming other columns are populated")
        .longOpt("only-merge")
    );
    add(Option.builder(OPTION_OPEN_EXISTING_OKAY)
        .desc("Use an existing index directory.  By default, indexes must be created in one shot.")
        .longOpt("use-existing")
    );
    add(Option.builder("h")
        .argName("help")
        .desc("Prints this help message")
        .longOpt("help")
    );
  }};
  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public PubchemTTLMerger() {

  }

  private enum PC_RDF_DATA_FILE_CONFIG {
    HASH_TO_SYNONYM("pc_synonym_value", COLUMN_FAMILIES.HASH_TO_SYNONYMS,
        PC_RDF_DATA_TYPES.SYNONYM, PC_RDF_DATA_TYPES.LITERAL, false, null),
    HASH_TO_CID("pc_synonym2compound", COLUMN_FAMILIES.CID_TO_HASHES,
        PC_RDF_DATA_TYPES.SYNONYM, PC_RDF_DATA_TYPES.COMPOUND, true, null),
    HASH_TO_MESH("pc_synonym_topic", COLUMN_FAMILIES.HASH_TO_MESH,
        PC_RDF_DATA_TYPES.SYNONYM, PC_RDF_DATA_TYPES.MeSH, false, null),
    HASH_TO_SYNONYM_TYPE("pc_synonym_type", COLUMN_FAMILIES.HASH_TO_SYNONYM_TYPE,
        PC_RDF_DATA_TYPES.SYNONYM, PC_RDF_DATA_TYPES.SIO, false,
        (String x) -> PC_SYNONYM_TYPES.getByCheminfId(x).name()), // Map CHEMINF values to synonym type designators.
    ;

    private String filePrefix;
    private COLUMN_FAMILIES columnFamily;
    private PC_RDF_DATA_TYPES keyType;
    private PC_RDF_DATA_TYPES valType;
    private boolean reverseSubjectAndObject;
    private Function<String, String> valueTransformer;

    PC_RDF_DATA_FILE_CONFIG(String filePrefix, COLUMN_FAMILIES columnFamily,
                            PC_RDF_DATA_TYPES keyType, PC_RDF_DATA_TYPES valType,
                            boolean reverseSubjectAndObject, Function<String, String> valueTransformer) {
      this.filePrefix = filePrefix;
      this.columnFamily = columnFamily;
      this.keyType = keyType;
      this.valType = valType;
      this.reverseSubjectAndObject = reverseSubjectAndObject;
      this.valueTransformer = valueTransformer;
    }

    public static PC_RDF_DATA_FILE_CONFIG getDataTypeForFile(File file) {
      String name = file.getName();
      for (PC_RDF_DATA_FILE_CONFIG t : PC_RDF_DATA_FILE_CONFIG.values()) {
        if (name.startsWith(t.filePrefix)) {
          return t;
        }
      }
      return null;
    }

    public static AbstractRDFHandler makeHandlerForDataFile(
        Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles, File file) {
      PC_RDF_DATA_FILE_CONFIG config = getDataTypeForFile(file);
      if (config == null) {
        LOGGER.info("No handler config found for file %s", file.getAbsolutePath());
        return null;
      }
      LOGGER.info("Selected handler type %s for file %s", config.name(), file.getName());

      return new PCRDFHandler(
          dbAndHandles,
          config.columnFamily,
          config.keyType,
          config.valType,
          config.reverseSubjectAndObject,
          config.valueTransformer
      );
    }
  }

  /**
   * Each triple in the RDF files takes the form:
   * <pre>[subject namespace]:[subject value] predicate namespace]:[predicate value] [object namespace]:[object value] .</pre>
   * Some of the files contain multiple types of values, only some of which we want to store.  For example, the
   * `topics` file contains both MeSH ids and "concepts" (I'm not sure what the latter actually represents).  We can
   * identify the MeSH ids based on their namespace and throw everything else away.
   *
   * Additionally, rdf4j represents different types of values with different Java objects.  IRI stands for
   * "internationalized resource identifier" (https://www.w3.org/TR/rdf11-concepts/#dfn-iri), and acts as a pointer
   * or identifier in the PC synonym corpus.  Synonym string values are modeled as literals, which have some sort of
   * label in some language (we ignore the language for now).
   *
   * This enum is a map of the useful namespaces and associated rdf4j model types to the parts of the synonym corpus
   * we want to extract.  Check out their use in PC_RDF_DATA_FILE_CONFIG to see how these are mapped to the
   * subjects and objects of different files in the synonym corpus.
   */
  private enum PC_RDF_DATA_TYPES {
    SYNONYM("http://rdf.ncbi.nlm.nih.gov/pubchem/synonym/", PCRDFHandler.OBJECT_TYPE.IRI),
    MeSH("http://id.nlm.nih.gov/mesh/", PCRDFHandler.OBJECT_TYPE.IRI),
    COMPOUND("http://rdf.ncbi.nlm.nih.gov/pubchem/compound/", PCRDFHandler.OBJECT_TYPE.IRI),
    LITERAL("langString", PCRDFHandler.OBJECT_TYPE.LITERAL),
    SIO("http://semanticscience.org/resource/", PCRDFHandler.OBJECT_TYPE.IRI),
    ;

    private String urlOrDatatypeName;
    /* We only expect one kind of RDF value object at a time depending on the value's namespace, so constrain to that
     * to allow proper dispatch within the handler. */
    private PCRDFHandler.OBJECT_TYPE valueObjectType;

    PC_RDF_DATA_TYPES(String urlOrDatatypeName, PCRDFHandler.OBJECT_TYPE valueObjectType) {
      this.urlOrDatatypeName = urlOrDatatypeName;
      this.valueObjectType = valueObjectType;
    }

    public String getUrlOrDatatypeName() {
      return this.urlOrDatatypeName;
    }

    public PCRDFHandler.OBJECT_TYPE getValueObjectType() {
      return this.valueObjectType;
    }
  }

  public enum COLUMN_FAMILIES {
    HASH_TO_SYNONYMS("hash_to_synonym"),
    CID_TO_HASHES("cid_to_hashes"),
    HASH_TO_MESH("hash_to_MeSH"),
    CID_TO_SYNONYMS("cid_to_synonyms"),
    HASH_TO_SYNONYM_TYPE("hash_to_synonym_type")
    ;

    private static final Map<String, COLUMN_FAMILIES> NAME_MAPPING = Collections.unmodifiableMap(
        new HashMap<String, COLUMN_FAMILIES>() {{
          for (COLUMN_FAMILIES family : COLUMN_FAMILIES.values()) {
            put(family.name, family);
          }
        }}
    );

    public static COLUMN_FAMILIES getFamilyByName(String name) {
      return NAME_MAPPING.get(name);
    }

    private String name;

    COLUMN_FAMILIES(String name) {
      this.name = name;
    }

    public String getName() {
      return this.name;
    }
  }

  // Note: @JsonSerialize and @JsonDeserialize didn't work here, so I've used @JsonCreator and @JsonValue instead.
  public enum PC_SYNONYM_TYPES {
    // Names derived from the Semantic Chemistry Ontology: https://github.com/egonw/semanticchemistry
    TRIVIAL_NAME("CHEMINF_000109", "trivial name", "trivial_name"),
    DEPOSITORY_NAME("CHEMINF_000339", "depositor-supplied name", "depositor_supplied_name"),
    IUPAC_NAME("CHEMINF_000382", "IUPAC name (LexiChem)", "IUPAC_name"),
    DRUG_BANK_ID("CHEMINF_000406", "DrugBank ID", "drugbank_id"),
    CHEBI_ID("CHEMINF_000407", "ChEBI ID", "ChEBI_id"),
    KEGG_ID("CHEMINF_000409", "KEGG ID", "KEGG_ID"),
    CHEMBL_ID("CHEMINF_000412", "ChEMBL ID", "ChEMBL_id"),
    CAS_REGISTRY_NUMBER("CHEMINF_000446", "CAS registry number", "cas_number"),
    EC_NUMBER("CHEMINF_000447", "EC number", "ec_number"),
    VALIDATED_CHEM_DB_ID("CHEMINF_000467", "Validated chemical database ID", "chem_db_id"),
    DRUG_TRADE_NAME("CHEMINF_000561", "Drug trade name", "trade_name"),
    INTL_NONPROPRIETARY_NAME("CHEMINF_000562", "International non-proprietary name", "non_proprietary_name"),
    UNIQUE_INGREDIENT_ID("CHEMINF_000563", "Unique ingredient ID", "unique_ingredient_id"),
    LIPID_MAPS_ID("CHEMINF_000564", "LipidMaps ID", "lipidmaps_id"),
    NSC_NUMBER("CHEMINF_000565", "National Service Center number", "nsc_number"),
    RTECS_ID("CHEMINF_000566", "RTECS ID", "RTECS_id"),
    UNKNOWN("NO_ID", "Unknown", "unknown")
    ;

    private static final Map<String, PC_SYNONYM_TYPES> CHEMINF_TO_TYPE = new HashMap<String, PC_SYNONYM_TYPES>() {{
      for (PC_SYNONYM_TYPES type : PC_SYNONYM_TYPES.values()) {
        put(type.getCheminfId(), type);
      }
    }};

    private static final Map<String, PC_SYNONYM_TYPES> JSON_LABEL_TO_TYPE = new HashMap<String, PC_SYNONYM_TYPES>() {{
      for (PC_SYNONYM_TYPES type : PC_SYNONYM_TYPES.values()) {
        put(type.getJsonLabel(), type);
      }
    }};

    public static PC_SYNONYM_TYPES getByCheminfId(String cheminfId) {
      return CHEMINF_TO_TYPE.getOrDefault(cheminfId, UNKNOWN);
    }

    @JsonCreator
    public static PC_SYNONYM_TYPES getByJsonLabel(String cheminfId) {
      return JSON_LABEL_TO_TYPE.getOrDefault(cheminfId, UNKNOWN);
    }

    String cheminfId;
    String label;
    String jsonLabel;

    PC_SYNONYM_TYPES(String cheminfId, String label, String jsonLabel) {
      this.cheminfId = cheminfId;
      this.label = label;
      this.jsonLabel = jsonLabel;
    }

    public String getCheminfId() {
      return cheminfId;
    }

    public String getLabel() {
      return label;
    }

    @JsonValue
    public String getJsonLabel() {
      return jsonLabel;
    }
  }

  private static class PCRDFHandler extends AbstractRDFHandler {
    public static final Double MS_PER_S = 1000.0;
    /* The Pubchem RDF corpus represents all subjects as SimpleIRIs, but objects can be IRIs or literals.  Let the child
     * class decide which one it wants to handle. */
    enum OBJECT_TYPE {
      IRI,
      LITERAL,
      ;
    }

    private RocksDB db;
    private COLUMN_FAMILIES columnFamily;
    private ColumnFamilyHandle cfh;
    // Filter out RDF types (based on namespace) that we don't recognize or don't want to process.
    PC_RDF_DATA_TYPES keyType, valueType;
    boolean reverseSubjectAndObject;
    /* This is a super janky way to map synonym types to their enum values in the index.  Would be better done with a
     * subclass, but we'll leave that for a refactoring once we get this working. */
    Function<String, String> valueTransformer = null;

    DateTime startTime;
    // Is the RDF parser single threaded?  We don't know, so use an atomic counter to be safe.
    AtomicLong numProcessed = new AtomicLong(0);
    // Store unrecognized namespaces so we only log once per RDF file, rather than once per entry (which is a lot).
    Set<String> seenUnrecognizedSubjectNamespaces = new HashSet<>();
    Set<String> seenUnrecognizedObjectNamespaces = new HashSet<>();

    PCRDFHandler(Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles, COLUMN_FAMILIES columnFamily,
                 PC_RDF_DATA_TYPES keyType, PC_RDF_DATA_TYPES valueType, boolean reverseSubjectAndObject,
                 Function<String, String> valueTransformer) {
      this.db = dbAndHandles.getLeft();
      this.columnFamily = columnFamily;
      this.cfh = dbAndHandles.getRight().get(columnFamily);
      this.keyType = keyType;
      this.valueType = valueType;
      this.reverseSubjectAndObject = reverseSubjectAndObject;
      this.valueTransformer = valueTransformer;
    }

    @Override
    public void startRDF() throws RDFHandlerException {
      super.startRDF();
      startTime = new DateTime().withZone(DateTimeZone.UTC);
    }

    @Override
    public void endRDF() throws RDFHandlerException {
      super.endRDF();
      DateTime endTime = new DateTime().withZone(DateTimeZone.UTC);
      Long runtimeInMilis = endTime.getMillis() - startTime.getMillis();
      Long numProcessedVal = numProcessed.get();
      LOGGER.info("PCRDFHandler reached end of RDF with %d events in %.3fs, at %.3f ms per event",
          numProcessedVal,
          runtimeInMilis.floatValue() / MS_PER_S,
          runtimeInMilis.doubleValue() / numProcessedVal.doubleValue()
      );
      try {
        db.flush(new FlushOptions().setWaitForFlush(true));
      } catch (RocksDBException e) {
        LOGGER.error("Caught RocksDB exception when flushing after completing RDF processing: %s", e.getMessage());
        throw new RDFHandlerException(e);
      }
    }

    @Override
    public void handleStatement(Statement st) {
      if (!(st.getSubject() instanceof SimpleIRI)) {
        // If we can't even recognize the type of the subject, something is very wrong.
        String msg = String.format("Unknown type of subject: %s", st.getSubject().getClass().getCanonicalName());
        LOGGER.error(msg);
        throw new RuntimeException(msg);
      }

      SimpleIRI subjectIRI = (SimpleIRI) st.getSubject();
      // Filter out keys in namespaces we're not interested in.
      if (!(keyType.getUrlOrDatatypeName().equals(subjectIRI.getNamespace()))) {
        // If we don't recognize the namespace of the subject, then we probably can't handle this triple.
        if (!seenUnrecognizedSubjectNamespaces.contains(subjectIRI.getNamespace())) {
          seenUnrecognizedSubjectNamespaces.add(subjectIRI.getNamespace());
          LOGGER.warn("Unrecognized subject namespace: %s\n", subjectIRI.getNamespace());
        }
        return;
      }

      String subject = subjectIRI.getLocalName();
      String object = null;
      // Let the subclasses tell us what
      if (this.valueType.getValueObjectType() == OBJECT_TYPE.IRI && st.getObject() instanceof SimpleIRI) {
        SimpleIRI objectIRI = (SimpleIRI) st.getObject();
        if (!valueType.getUrlOrDatatypeName().equals(objectIRI.getNamespace())) {
          // If we don't recognize the namespace of the subject, then we probably can't handle this triple.
          if (!seenUnrecognizedObjectNamespaces.contains(objectIRI.getNamespace())) {
            seenUnrecognizedObjectNamespaces.add(objectIRI.getNamespace());
            LOGGER.warn("Unrecognized object namespace: %s\n", objectIRI.getNamespace());
          }
          return;
        }
        object = objectIRI.getLocalName();
      } else if (this.valueType.getValueObjectType() == OBJECT_TYPE.LITERAL &&
          st.getObject() instanceof SimpleLiteral) {
        SimpleLiteral objectLiteral = (SimpleLiteral) st.getObject();
        IRI datatype = objectLiteral.getDatatype();
        if (!valueType.getUrlOrDatatypeName().equals(datatype.getLocalName())) {
          // We're only expecting string values where we find literals.
          if (!seenUnrecognizedObjectNamespaces.contains(datatype.getLocalName())) {
            seenUnrecognizedObjectNamespaces.add(datatype.getLocalName());
            LOGGER.warn("Unrecognized simple literal datatype: %s\n", datatype.getLocalName());
          }
          return;
        }
        object = objectLiteral.getLabel();
      } else {
        String msg = String.format("Unknown type of object: %s", st.getObject().getClass().getCanonicalName());
        LOGGER.error(msg);
        throw new RuntimeException(msg);
      }

      /* I considered modeling this decision using subclasses, but it made the configuration to much of a pain.  Maybe
       * we'll do something clever the next time this code needs modification... */
      Pair<String, String> kvPair;
      if (reverseSubjectAndObject) {
        // If the keys, like PC ids, are on the right, we need to swap them around before storing.
        kvPair = Pair.of(object, subject);
      } else {
        kvPair = Pair.of(subject, object);
      }

      if (valueTransformer != null) {
        kvPair = Pair.of(kvPair.getKey(), valueTransformer.apply(kvPair.getValue()));
      }

      // Store the key and value in the appropriate column family.
      appendValueToList(db, cfh, kvPair.getKey(), kvPair.getValue());
      numProcessed.incrementAndGet();
    }

    private void appendValueToList(RocksDB db, ColumnFamilyHandle cfh, String key, String val) {
      StringBuffer buffer = new StringBuffer();
      List<String> storedObjects = null;
      byte[] keyBytes = key.getBytes(UTF8);
      // TODO: pull this out into a helper class or interface.  Alas, we can must extend the AbstractRDFHandler.
      try {
        if (db.keyMayExist(cfh, keyBytes, buffer)) {
          byte[] existingVal = db.get(cfh, keyBytes);
          if (existingVal != null) {
            ObjectInputStream oi = new ObjectInputStream(new ByteArrayInputStream(existingVal));
            storedObjects = (ArrayList<String>) oi.readObject(); // Note: assumes all values are lists.
            /* Once upon a time I had a constraint here that crashed if we expected unique keys.  This was mainly to
             * guard against hypothetical synonym hash collisions.  What ends up happening, however, is that Pubchem
             * stores multiple values of one hash with different normalizations (like all uppercase or all lowercase)
             * meaning there *will* be multiple values with the same hash, but these values will all be valid.
             * Instead we just ignore potential hash collisions and assume that any "collisions" are intentional. */
          } else {
            storedObjects = new ArrayList<>(1);
          }
        } else {
          storedObjects = new ArrayList<>(1);
        }

        storedObjects.add(val);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oo = new ObjectOutputStream(bos)) {
          oo.writeObject(storedObjects);
          oo.flush();

          db.put(cfh, new WriteOptions(), keyBytes, bos.toByteArray());
        }
      } catch (RocksDBException e) {
        LOGGER.error("Caughted unexpected RocksDBException: %s", e.getMessage());
        throw new RuntimeException(e);
      } catch (IOException e) {
        LOGGER.error("Caughted unexpected IOException: %s", e.getMessage());
        throw new RuntimeException(e);
      } catch (ClassNotFoundException e) {
        LOGGER.error("Caughted unexpected ClassNotFoundEXception: %s", e.getMessage());
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    org.apache.commons.cli.Options opts = new org.apache.commons.cli.Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    PubchemTTLMerger merger = new PubchemTTLMerger();

    File rocksDBFile = new File(cl.getOptionValue(OPTION_INDEX_PATH));

    if (cl.hasOption(OPTION_ONLY_MERGE)) {
      if (!(rocksDBFile.exists() && rocksDBFile.isDirectory())) {
        System.err.format("Must specify an existing RocksDB index when using '%s'.\n", OPTION_ONLY_MERGE);
        HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
        System.exit(1);
      }
      merger.finish(merger.merge(rocksDBFile));
      return;
    }

    File rdfDir = new File(cl.getOptionValue(OPTION_RDF_DIRECTORY));
    if (!rdfDir.isDirectory()) {
      System.err.format("Must specify a directory of RDF files to be parsed.\n");
      HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    File[] filesInDirectoryArray = rdfDir.listFiles(new FilenameFilter() {
      private static final String TTL_GZ_SUFFIX = ".ttl.gz";
      @Override
      public boolean accept(File dir, String name) {
        return name.endsWith(TTL_GZ_SUFFIX);
      }
    });

    if (filesInDirectoryArray == null || filesInDirectoryArray.length == 0) {
      System.err.format("Found zero compressed TTL files in directory at '%s'.\n", rdfDir.getAbsolutePath());
      HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    // Sort files for stability/sanity.
    List<File> filesInDirectory = Arrays.asList(filesInDirectoryArray);
    Collections.sort(filesInDirectory);

    if (cl.hasOption(OPTION_ONLY_SYNONYMS)) {
      filesInDirectory = filterByFileContents(filesInDirectory, PC_RDF_DATA_FILE_CONFIG.HASH_TO_SYNONYM);
    }

    if (cl.hasOption(OPTION_ONLY_MESH)) {
      filesInDirectory = filterByFileContents(filesInDirectory, PC_RDF_DATA_FILE_CONFIG.HASH_TO_MESH);
    }

    if (cl.hasOption(OPTION_ONLY_PUBCHEM_IDS)) {
      filesInDirectory = filterByFileContents(filesInDirectory, PC_RDF_DATA_FILE_CONFIG.HASH_TO_CID);
    }

    if (filesInDirectory.size() == 0) {
      System.err.format("Arrived at index initialization with no files to process.  " +
              "Maybe too many filters were specified?  synonyms: %s, MeSH: %s, Pubchem ids: %s\n",
          cl.hasOption(OPTION_ONLY_SYNONYMS), cl.hasOption(OPTION_ONLY_MESH), cl.hasOption(OPTION_ONLY_PUBCHEM_IDS));
      HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    RocksDB.loadLibrary();
    Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles = null;
    try {
      if (rocksDBFile.exists()) {
        if (!cl.hasOption(OPTION_OPEN_EXISTING_OKAY)) {
          System.err.format(
              "Index directory at '%s' already exists, delete before retrying or add '%s' option to reuse.\n",
              rocksDBFile.getAbsolutePath(), OPTION_OPEN_EXISTING_OKAY);
          HELP_FORMATTER.printHelp(PubchemTTLMerger.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
          System.exit(1);
        } else {
          LOGGER.info("Reusing existing index at %s", rocksDBFile.getAbsolutePath());
          dbAndHandles = openExistingRocksDB(rocksDBFile);
        }
      } else {
        LOGGER.info("Creating new index at %s", rocksDBFile.getAbsolutePath());
        dbAndHandles = createNewRocksDB(rocksDBFile);
      }
      merger.buildIndex(dbAndHandles, filesInDirectory);

      merger.merge(dbAndHandles);
    } finally {
      if (dbAndHandles != null) {
        merger.finish(dbAndHandles);
      }
    }
  }

  protected static List<File> filterByFileContents(List<File> files, PC_RDF_DATA_FILE_CONFIG fileConfig) {
    return files.stream().filter(x -> x.getName().startsWith(fileConfig.filePrefix)).collect(Collectors.toList());
  }

  protected static Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> createNewRocksDB(File pathToIndex)
      throws RocksDBException {
    RocksDB db = null; // Not auto-closable.
    Map<COLUMN_FAMILIES, ColumnFamilyHandle> columnFamilyHandles = new HashMap<>();

    Options options = ROCKS_DB_CREATE_OPTIONS;
    System.out.println("Opening index at " + pathToIndex.getAbsolutePath());
    db = RocksDB.open(options, pathToIndex.getAbsolutePath());

    for (COLUMN_FAMILIES cf : COLUMN_FAMILIES.values()) {
      LOGGER.info("Creating column family %s", cf.getName());
      ColumnFamilyHandle cfh =
          db.createColumnFamily(new ColumnFamilyDescriptor(cf.getName().getBytes(UTF8)));
      columnFamilyHandles.put(cf, cfh);
    }

    return Pair.of(db, columnFamilyHandles);
  }

  /**
   * Open an existing RocksDB index.  Use this after successful index generation to access the map of Pubchem compound
   * ids to synonyms/MeSH ids using the column family CID_TO_SYNONYMS.
   * @param pathToIndex A path to the RocksDB index directory to use.
   * @return
   * @throws RocksDBException
   */
  public static Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> openExistingRocksDB(File pathToIndex)
      throws RocksDBException {
    List<ColumnFamilyDescriptor> columnFamilyDescriptors = new ArrayList<>(COLUMN_FAMILIES.values().length + 1);
    // Must also open the "default" family or RocksDB will probably choke.
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor(DEFAULT_ROCKSDB_COLUMN_FAMILY.getBytes()));
    for (COLUMN_FAMILIES family : COLUMN_FAMILIES.values()) {
      columnFamilyDescriptors.add(new ColumnFamilyDescriptor(family.getName().getBytes()));
    }
    List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>(columnFamilyDescriptors.size());

    DBOptions dbOptions = ROCKS_DB_OPEN_OPTIONS;
    dbOptions.setCreateIfMissing(false);
    RocksDB rocksDB = RocksDB.open(dbOptions, pathToIndex.getAbsolutePath(),
        columnFamilyDescriptors, columnFamilyHandles);
    Map<COLUMN_FAMILIES, ColumnFamilyHandle> columnFamilyHandleMap = new HashMap<>(COLUMN_FAMILIES.values().length);
    // TODO: can we zip these together more easily w/ Java 8?

    for (int i = 0; i < columnFamilyDescriptors.size(); i++) {
      ColumnFamilyDescriptor cfd = columnFamilyDescriptors.get(i);
      ColumnFamilyHandle cfh = columnFamilyHandles.get(i);
      String familyName = new String(cfd.columnFamilyName(), UTF8);
      COLUMN_FAMILIES descriptorFamily = COLUMN_FAMILIES.getFamilyByName(familyName);
      if (descriptorFamily == null) {
        if (!DEFAULT_ROCKSDB_COLUMN_FAMILY.equals(familyName)) {
          String msg = String.format("Found unexpected family name '%s' when trying to open RocksDB at %s",
              familyName, pathToIndex.getAbsolutePath());
          LOGGER.error(msg);
          // Crash if we don't recognize the contents of this DB.
          throw new RuntimeException(msg);
        }
        // Just skip this column family if it doesn't map to something we know but is expected.
        continue;
      }

      columnFamilyHandleMap.put(descriptorFamily, cfh);
    }

    return Pair.of(rocksDB, columnFamilyHandleMap);
  }

  protected Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> merge(File pathToRocksDB)
      throws RocksDBException, IOException, ClassNotFoundException {
    Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles = openExistingRocksDB(pathToRocksDB);
    merge(dbAndHandles);
    return dbAndHandles;
  }

  protected void merge(Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles)
      throws RocksDBException, IOException, ClassNotFoundException {
    LOGGER.info("Beginning merge on Pubchem CID");
    RocksDB db = dbAndHandles.getLeft();
    ColumnFamilyHandle pubchemIdCFH = dbAndHandles.getRight().get(COLUMN_FAMILIES.CID_TO_HASHES);
    ColumnFamilyHandle meshCFH = dbAndHandles.getRight().get(COLUMN_FAMILIES.HASH_TO_MESH);
    ColumnFamilyHandle synonymCFH = dbAndHandles.getRight().get(COLUMN_FAMILIES.HASH_TO_SYNONYMS);
    ColumnFamilyHandle synonymTypeCFH = dbAndHandles.getRight().get(COLUMN_FAMILIES.HASH_TO_SYNONYM_TYPE);
    ColumnFamilyHandle mergeResultsCFH = dbAndHandles.getRight().get(COLUMN_FAMILIES.CID_TO_SYNONYMS);

    RocksIterator cidIterator = db.newIterator(pubchemIdCFH);
    // With help from https://github.com/facebook/rocksdb/wiki/Basic-Operations
    int processed = 0;
    for (cidIterator.seekToFirst(); cidIterator.isValid(); cidIterator.next()) {
      byte[] key = cidIterator.key();
      byte[] val = cidIterator.value();
      String pubchemId = new String(key, UTF8);
      List<String> hashes;
      try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(val))) {
        // We know all our values so far have been lists of strings, so this should be completely safe.
        hashes = (List<String>) ois.readObject();
      }

      PubchemSynonyms pubchemSynonyms = new PubchemSynonyms(pubchemId);

      /* The hash keys are based on synonym value, which we can manually compute with:
       *   $ echo -n  'dimethyltin(iv)' | md5
       * This means that MeSH ids are linked to synonyms rather than pubchem ids.  We need to look up each cid-linked
       * hash in both the MeSH and synonym collections, as the key may legitimately exist in both (and serve to link
       * cid to synonym and cid to MeSH). */
      for (String hash : hashes) {
        /* Note: these ids are not proper MeSH topic ids, but are internal MeSH ids found in the RDF and TTL
         * representations of the MeSH corpus.  You can find them in the MeSH .nt or .xml files, but they won't turn up
         * anything on the MeSH website. */
        List<String> meshIds = getValueAsObject(db, meshCFH, hash);
        if (meshIds != null) {
          pubchemSynonyms.addMeSHIds(meshIds);
        }

        List<String> synonyms = getValueAsObject(db, synonymCFH, hash);
        // There are, surprisingly, some dangling hashes in the DB!  Handle them gracefully.
        if (synonyms == null) {
          LOGGER.warn("Dangling synonym hash reference, adding empty list in place of value: cid = %s, hash = %s",
              pubchemId, hash);
          synonyms = Collections.emptyList();
        }

        List<String> synonymTypeStrings = getValueAsObject(db, synonymTypeCFH, hash);
        Set<PC_SYNONYM_TYPES> synonymTypes = DEFAULT_SYNONYM_DATA_TYPES;
        if (synonymTypeStrings != null) {
          synonymTypes = synonymTypeStrings.stream().map(PC_SYNONYM_TYPES::valueOf).collect(Collectors.toSet());
        }

        if (synonymTypes.size() == 0) {
          LOGGER.warn("Found zero synonym types for synonym, defaulting to %s: %s %s, synonyms = %s",
              PC_SYNONYM_TYPES.UNKNOWN.name(), pubchemId, hash, StringUtils.join(synonyms, ", "));
        }
        /* It turns out that *lots* of synonyms are duplicated as depositor supplied names, so don't complain about it
         * here.  For performance sake we might want to consider changing the data model of PubchemSynonyms to reduce
         * synonym string duplication, as the current model is pretty inefficient. */

        for (PC_SYNONYM_TYPES synonymType : synonymTypes) {
          for (String synonym : synonyms) {
            // Let the PubchemSynonyms object do the de-duplication for us rather than reducing `synonyms` to a Set.
            pubchemSynonyms.addSynonym(synonymType, synonym);
          }
        }
      }

      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
           ObjectOutputStream oo = new ObjectOutputStream(bos)) {
        oo.writeObject(pubchemSynonyms);
        oo.flush();

        db.put(mergeResultsCFH, key, bos.toByteArray());
      }

      processed++;
      if (processed % 100000 == 0) {
        LOGGER.info("Merged %d entries on Pubchem compound id", processed);
      }
    }
    LOGGER.info("Merge complete, %d entries processed", processed);
  }

  protected <T> T getValueAsObject(RocksDB db, ColumnFamilyHandle cfh, String key)
      throws RocksDBException, ClassNotFoundException, IOException {
    StringBuffer stringBuffer = new StringBuffer();
    T val = null;
    /* Check for existence before fetching.  IIRC doing otherwise might cause segfaults in the RocksDB JNI wrapper.
     * Or it might just be faster thanks to the DB's bloom filter. */
    if (db.keyMayExist(cfh, key.getBytes(), stringBuffer)) {
      byte[] valBytes = db.get(cfh, key.getBytes());
      // Make sure that the key actually exist (beware the "May" in keyMayExist).
      if (valBytes != null) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(valBytes))) {
          val = (T) ois.readObject();
        }
      }
    }
    return val;
  }

  protected void buildIndex(Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles, List<File> rdfFiles)
      throws RocksDBException, ClassNotFoundException, IOException {
    LOGGER.info("Building RocksDB index of data in RDF files");
    RDFParser parser = Rio.createParser(RDFFormat.TURTLE);

    LOGGER.info("Processing %d RDF files", rdfFiles.size());
    for (File rdfFile : rdfFiles) {
      LOGGER.info("Processing file %s", rdfFile.getAbsolutePath());
      AbstractRDFHandler handler = PC_RDF_DATA_FILE_CONFIG.makeHandlerForDataFile(dbAndHandles, rdfFile);
      if (handler == null) {
        LOGGER.info("Skipping file without defined handler: %s", rdfFile.getAbsolutePath());
        continue;
      }

      parser.setRDFHandler(handler);
      parser.parse(new GZIPInputStream(new FileInputStream(rdfFile)), "");
      LOGGER.info("Successfully parsed file at %s", rdfFile.getAbsolutePath());
    }
    LOGGER.info("Done processing RDF files");
  }

  protected void finish(Pair<RocksDB, Map<COLUMN_FAMILIES, ColumnFamilyHandle>> dbAndHandles) {
    LOGGER.info("Closing DB to complete merge.");
    dbAndHandles.getLeft().close();
  }
}
