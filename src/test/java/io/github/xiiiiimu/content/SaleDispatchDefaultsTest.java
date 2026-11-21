package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.SaleDispatcher;
import io.github.xiiiiimu.content.domain.SaleFaultHooks;
import io.github.xiiiiimu.content.domain.SaleIngress;
import io.github.xiiiiimu.content.domain.SaleService;
import io.github.xiiiiimu.content.infrastructure.SaleConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Default (report campaign-lock-v26-clean-20261120, tier B2): batch dispatch, cap 20, concurrency 2.
 * Concurrency stays configurable (1 = former tier B) and serial stays available as a switch.
 */
class SaleDispatchDefaultsTest {
 private final ApplicationContextRunner runner=new ApplicationContextRunner()
  .withUserConfiguration(SaleConfiguration.Dispatch.class)
  .withBean(SaleService.class,()->mock(SaleService.class))
  .withBean(SaleIngress.class,()->mock(SaleIngress.class))
  .withBean(SaleFaultHooks.class,SaleFaultHooks::new);

 @Test void defaultIsBatchWithCapTwentyAndTwoSenders(){
  runner.run(ctx->{
   assertThat(ctx).as("no dispatch-mode property means batch dispatch").hasSingleBean(SaleDispatcher.class);
   var d=ctx.getBean(SaleDispatcher.class);
   assertThat(d.batchMax()).isEqualTo(20);
   assertThat(d.concurrency()).as("B2: two sender slots by default").isEqualTo(2);
   assertThat(d.slowStartSends()).as("producer warm-up guard on by default").isEqualTo(4);
  });
 }
 @Test void concurrencyStaysConfigurable(){
  runner.withPropertyValues("content.sale.dispatch-concurrency=4").run(ctx->{
   assertThat(ctx.getBean(SaleDispatcher.class).concurrency()).isEqualTo(4);
   assertThat(ctx.getBean(SaleDispatcher.class).batchMax()).isEqualTo(20);
  });
 }
 @Test void singleSenderRemainsAvailable(){
  runner.withPropertyValues("content.sale.dispatch-concurrency=1").run(ctx->
   assertThat(ctx.getBean(SaleDispatcher.class).concurrency()).isEqualTo(1));
 }
 @Test void serialRemainsAvailableAsASwitch(){
  runner.withPropertyValues("content.sale.dispatch-mode=serial").run(ctx->assertThat(ctx).doesNotHaveBean(SaleDispatcher.class));
 }
}
