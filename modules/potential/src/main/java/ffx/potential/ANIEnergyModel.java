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

    private final double HARTREE_TO_KCAL_MOL_MULTIPLIER = 627.503;

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

    public void run(final String modelType) throws MalformedModelException, IOException {
        final Model model = loadModel(ANI_MAP.get(modelType));
        try(NDManager manager = NDManager.newBaseManager()){
            NDArray species = manager.create(new long[]{6, 1, 1, 1, 1}, new Shape(5));
            species.setName("input1()");
            NDArray coordinates = manager.create(new float[]{0.03192167f, 0.00638559f, 0.01301679f,
                                                             -0.83140486f, 0.39370209f, -0.26395324f,
                                                             -0.66518241f, -0.84461308f, 0.20759389f,
                                                             0.45554739f, 0.54289633f, 0.81170881f,
                                                             0.66091919f, -0.16799635f, -0.91037834f},
                                                 new Shape(5,3));
            coordinates.setName("input1()");
            final NDList input = new NDList(species, coordinates);
            input.attach(manager);
            final double hartreeEnergy = predictEnergies(model, input);
            final double kcalPerMolEnergy = hartreeToKcalPerMol(hartreeEnergy);
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
