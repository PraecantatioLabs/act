package com.act.biointerpretation.sars;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SarCorpus implements Iterable<CharacterizedGroup> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
  }

  private static final Logger LOGGER = LogManager.getFormatterLogger(SarCorpus.class);

  Iterable<ReactionGroup> enzymeGroups;
  EnzymeGroupCharacterizer characterizer;

  @JsonProperty
  List<CharacterizedGroup> characterizedGroups;

  /**
   * For JSON reading.
   */
  private SarCorpus() {
  }

  public SarCorpus(Iterable<ReactionGroup> enzymeGroups, EnzymeGroupCharacterizer characterizer) {
    this.enzymeGroups = enzymeGroups;
    this.characterizer = characterizer;
    characterizedGroups = new ArrayList<>();
  }

  /**
   * Builds SAR corpus by applying the EnzymeGroupCharacterizer to every supplied ReactionGroup that it can.
   */
  public void buildSarCorpus() throws IOException {
    int counter = 1;

    for (ReactionGroup group : enzymeGroups) {
      List<CharacterizedGroup> characterizations = characterizer.characterizeGroup(group);
      for (CharacterizedGroup characterization : characterizations) {
        characterizedGroups.add(characterization);
      }

      if (counter % 1 == 0) {
        LOGGER.info("Processed group %d, characterized %d so far.", counter, characterizedGroups.size());
      }
      counter++;
    }
  }

  public void printToJsonFile(File outputFile) throws IOException {
    try (BufferedWriter predictionWriter = new BufferedWriter(new FileWriter(outputFile))) {
      OBJECT_MAPPER.writeValue(predictionWriter, this);
    }
  }

  /**
   * Read a sar corpus from file.
   *
   * @param corpusFile The file to read.
   * @return The SarCorpus.
   * @throws IOException
   */
  public static SarCorpus readCorpusFromJsonFile(File corpusFile) throws IOException {
    return OBJECT_MAPPER.readValue(corpusFile, SarCorpus.class);
  }

  /**
   * Returns an iterator over the characterized groups generated by this corpus.
   *
   * @return The iterator.
   */
  @Override
  public Iterator<CharacterizedGroup> iterator() {
    return characterizedGroups.iterator();
  }
}
