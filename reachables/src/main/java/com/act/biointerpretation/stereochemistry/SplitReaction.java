package com.act.biointerpretation.stereochemistry;

import chemaxon.formats.MolImporter;
import chemaxon.license.LicenseManager;
import chemaxon.license.LicenseProcessingException;
import chemaxon.struc.MolAtom;
import chemaxon.struc.Molecule;
import chemaxon.struc.RxnMolecule;
import com.act.biointerpretation.utils.ChemAxonUtils;
import com.act.biointerpretation.utils.FileUtils;
import com.chemaxon.mapper.AutoMapper;

import java.io.File;
import java.util.*;

/**
 * Created by jca20n on 10/26/15.
 */
public class SplitReaction {
    SplitChem substrate;
    SplitChem product;
    int[] transforms;
    boolean[] inversions;

    public static void main(String[] args) throws Exception {
        ChemAxonUtils.license();
        testMenthol();
    }

    private static void testMenthol() {

        //However, the provided reaction is stated as racemic, represented in SUU form with ?'s
        String racemicMenthol = "InChI=1S/C10H20O/c1-7(2)9-5-4-8(3)6-10(9)11/h7-11H,4-6H2,1-3H3/t8?,9?,10?";
        String racemicProduct = "InChI=1S/C17H24O2/c1-12(2)15-10-9-13(3)11-16(15)19-17(18)14-7-5-4-6-8-14/h4-8,12-13,15-16H,9-11H2,1-3H3/t13?,15?,16?";

        //Create the reaction
        SplitReaction rxn = SplitReaction.generate(racemicMenthol, racemicProduct);
        System.out.println(rxn.toString());
    }

    private static void testTartrate() {
        String reactantInchi = "InChI=1S/C4H6O6/c5-1(3(7)8)2(6)4(9)10/h1-2,5-6H,(H,7,8)(H,9,10)/t1?,2?";
        String productInchi = "InChI=1/C5H8O6/c1-11-5(10)3(7)2(6)4(8)9/h2-3,6-7H,1H3,(H,8,9)/t2?,3?";

        //Create the reaction
        SplitReaction rxn = SplitReaction.generate(reactantInchi, productInchi);
        System.out.println(rxn.toString());
    }

    private static void testSerine() {
        //Our reaction is serine being methylated to methylserine.  The SAM is already dropped by inspection.
        String serine = "InChI=1S/C3H7NO3/c4-2(1-5)3(6)7/h2,5H,1,4H2,(H,6,7)/t2-/m1/s1";
        String methylserine = "InChI=1S/C4H9NO3/c1-5-3(2-6)4(7)8/h3,5-6H,2H2,1H3,(H,7,8)/t3-/m0/s1";

        //However, the provided reaction is stated as racemic, represented in SUU form with ?'s
        String racemicSerine = "InChI=1S/C3H7NO3/c4-2(1-5)3(6)7/h2,5H,1,4H2,(H,6,7)/t2?";
        String racemicMethylSerine = "InChI=1S/C4H9NO3/c1-5-3(2-6)4(7)8/h3,5-6H,2H2,1H3,(H,7,8)/t3?";

        //Create the reaction
        SplitReaction rxn = SplitReaction.generate(racemicSerine, racemicMethylSerine);
        System.out.println(rxn.toString());
    }

    private SplitReaction(SplitChem substrate, SplitChem product, int[] transforms, boolean[] inversions) {
        this.substrate = substrate;
        this.product = product;
        this.transforms = transforms;
        this.inversions = inversions;
    }

    public static SplitReaction generate(String subInchi, String prodInchi) {
//        System.out.println("inputs:");
//        System.out.println("\tSubstrate: " + subInchi);
//        System.out.println("\tProduct  : " + prodInchi);

        SplitChem substrate = SplitChem.generate(subInchi);
        SplitChem product = SplitChem.generate(prodInchi);


        try {
            //Do atom to atom mapping to figure our the transforms
            int[] transforms = calculateTransforms(substrate, product);

            //Calculate whether each stereocenter should be inverted
            boolean[] inversions = calculateInversions(substrate, product);

            return new SplitReaction(substrate, product, transforms, inversions);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static boolean[] calculateInversions(SplitChem substrate, SplitChem product)  throws Exception {
        //TODO:  FIGURE OUT WHAT TO DO HERE

        return new boolean[substrate.stereos.length];
    }

    private static int[] calculateTransforms(SplitChem substrate, SplitChem product) throws Exception {
        String subSmiles = ChemAxonUtils.InchiToSmiles(substrate.inchiBase);
        String prodSmiles = ChemAxonUtils.InchiToSmiles(product.inchiBase);
        String ro = subSmiles + ">>" + prodSmiles;

        //Use ChemAxon to create an atom-to-atom mapping
        RxnMolecule reaction = RxnMolecule.getReaction(MolImporter.importMol(ro));
        AutoMapper mapper = new AutoMapper();
        mapper.map(reaction);

        Molecule sub = reaction.getReactant(0);
        Molecule prod = reaction.getProduct(0);

        Map<Integer,Integer> subIndexToBin = new HashMap<>();
        Map<Integer,Integer> prodBinToMolIndex = new HashMap<>();
        Map<Integer,Integer> MolIndexToTransformIndex = new HashMap<>();

        for(int i=0; i<sub.getAtomCount(); i++) {
            //Drop the atom if it isn't a stereocenter
            if(sub.getChirality(i) == 0) {
                continue;
            }

            //Hash the atom:  key is the index in the ChemAxon Molecule, the 'Bin' is the index in the atom-to-atom map
            MolAtom atom = sub.getAtom(i);


            int mapIndex = atom.getAtomMap();
            subIndexToBin.put(i, mapIndex);
        }

        int transformIndex = 0;
        for(int i=0; i<prod.getAtomCount(); i++) {
            //Drop the atom if it isn't a stereocenter
            if(prod.getChirality(i) == 0) {
                continue;
            }

            //Hash the atom, map is inverted relative to the substrate map
            MolAtom atom = prod.getAtom(i);
            int mapIndex = atom.getAtomMap();
            prodBinToMolIndex.put(mapIndex, i);
            MolIndexToTransformIndex.put(i,transformIndex);
            transformIndex++;
        }

        //Create the transform for all indices
        int[] out = new int[subIndexToBin.size()];
        int index = 0;
        for(Integer i : subIndexToBin.keySet()) {
            int mapIndex = subIndexToBin.get(i);
            int prodIndex = prodBinToMolIndex.get(mapIndex);
            int transIndex = MolIndexToTransformIndex.get(prodIndex);
            out[index] = transIndex;
            index++;
        }
        return out;
    }

    public String toString() {
        String out = "Reaction data:\n\n";
        out += ">substrate:\n" + this.substrate.toString() + "\n";
        out += ">product:\n" + this.product.toString() + "\n";
        out += ">transform:\n";
        for(int i=0; i<transforms.length; i++) {
            out += i + " : " + transforms[i] + "\n";
        }

        out += "\n>inversions:\n";
        for(int i=0; i<inversions.length; i++) {
            out += i + " : " + inversions[i] + "\n";
        }
        return out;
    }

}