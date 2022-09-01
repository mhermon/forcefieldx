package ffx.potential;

import static java.lang.String.format;

import java.io.IOException;
import java.nio.file.*;
import ai.djl.*;
import ai.djl.ndarray.*;
import ai.djl.ndarray.types.*;
import ai.djl.translate.*;
import ai.djl.inference.*;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableMap;

public class ANIEnergyModel {

    private final double HARTREE_TO_KCAL_MOL_MULTIPLIER = 627.5094738898777;

    private final Path MODEL_DIR = Paths.get("modules/potential/src/main/java/ffx/potential/models");

    private static final Logger logger = Logger.getLogger(ANIEnergyModel.class.getName());

    private final ImmutableMap<String, String> ANI_MAP = ImmutableMap.of(
            "1", "ANI1x.pt",
            "1cc", "ANI1ccx.pt",
            "2", "ANI2x.pt"
    );

    private Translator<NDList, NDList> translator;

    /**
     * Constructor for the ANIEnergy class.
     */
    public ANIEnergyModel() {
        translator = new Translator<NDList, NDList>() {
            @Override
            public NDList processInput(TranslatorContext ctx, NDList input) {
                return input;
            }

            @Override
            public NDList processOutput(TranslatorContext ctx, NDList list) {
                return list;
            }

            @Override
            public Batchifier getBatchifier() {
                return Batchifier.STACK;
            }
        };
    }

    public void run(final String modelType, final long[] species, final float[] coordinates)
                    throws MalformedModelException, IOException {

        final Model model = loadModel(ANI_MAP.get(modelType));

        try (NDManager manager = NDManager.newBaseManager()) {
            final int numAtoms = species.length;

            NDArray speciesND = manager.create(species, new Shape(numAtoms));
            speciesND.setName("input1()");
            NDArray coordinatesND = manager.create(coordinates, new Shape(numAtoms, 3));
            coordinatesND.setName("input1()");
            final NDList input = new NDList(speciesND, coordinatesND);
            input.attach(manager);

            final double hartreeEnergy = predictEnergies(model, input);
            final double kcalPerMolEnergy = hartreeToKcalPerMol(hartreeEnergy);

            logger.info(format("Energy (Hartree): %f", hartreeEnergy));
            logger.info(format("Energy (KCal/Mol): %f", kcalPerMolEnergy));
        } catch (Exception e) {
            logger.throwing("ANIEnergyModel", "run", e);
            throw new RuntimeException(e);
        }
    }

    private double hartreeToKcalPerMol(final double hartreeEnergy) {
        return HARTREE_TO_KCAL_MOL_MULTIPLIER * hartreeEnergy;
    }

    private Model loadModel(final String modelFileName) throws IOException, MalformedModelException {
        logger.info(format("Loading model: %s", modelFileName));
        Model model = Model.newInstance("ANIModel");
        model.load(MODEL_DIR, modelFileName);
        logger.info("Model succesfully loaded.");
        return model;
    }

    private double predictEnergies(final Model model, final NDList input) throws TranslateException {
        logger.info("Running energy prediction...");
        final Predictor<NDList, NDList> predictor = model.newPredictor(translator);
        final NDList speciesEnergies = predictor.predict(input);
        final double hartreeEnergy = speciesEnergies.get(1).getDouble();
        return hartreeEnergy;
    }

}
