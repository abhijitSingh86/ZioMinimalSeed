import utils.network.{CircuitBreakerSpec, LoadBalancerSpec, RetryUtilSpec}
import zio.test.{DefaultRunnableSpec, suite}

object RunAllSpecs  extends DefaultRunnableSpec {
  def spec = suite("All Tests")(
    CircuitBreakerSpec.spec,
    LoadBalancerSpec.spec,
    RetryUtilSpec.spec
  )

}
