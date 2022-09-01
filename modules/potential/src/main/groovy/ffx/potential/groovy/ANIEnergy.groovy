package ffx.potential.groovy

import ffx.potential.cli.PotentialScript
import ffx.potential.ANIEnergyModel
import picocli.CommandLine.Option
import picocli.CommandLine.Command

/**
 * The ANIEnergy script evaluates the energy of a system according to the ANI models.
 * <br>
 * Usage:
 * <br>
 * ffxc ANIEnergy &lt;filename&gt;
 */

@Command(description = " Compute the ANI potential energy.", name = "ffxc ANIEnergy")
class ANIEnergy extends PotentialScript {

   /**
   * -m or --model ANI1x (1), ANI1ccx (1cc), or ANI2x (2).
   */
  @Option(names = ['-m', '--model'], paramLabel="ANI Model" , description = "The ANI model to use.", defaultValue = "1", 
      required = true, arity = "1")
  private String model;


  /**
   * ANIEnergy constructor.
   */
  ANIEnergy() {
    this(new Binding())
  }

  /**
   * ANIEnergy constructor.
   * @param binding The Groovy Binding to use.
   */
  ANIEnergy(Binding binding) {
    super(binding)
  }

  private ANIEnergyModel aniEnergyModel

  /**
   * Execute the script.
   */
  ANIEnergy run() {

    // Init the context and bind variables.
    if (!init()) {
      return this
    }

    ANIEnergyModel energyModel = new ANIEnergyModel()
    energyModel.run(model)

    return this
  }

}