package org.jenkinsci.plugins.mesos;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest(MesosComputer.class)
public class MesosRetentionStrategyTest {

  @Before
  public void initialize() {
    DateTimeUtils.setCurrentMillisFixed(new DateTime().getMillis());
  }

  @After
  public void cleanup() {
    DateTimeUtils.setCurrentMillisSystem();
  }

  @Test
  public void should_check_if_is_terminable() {
    assertThat(new MesosRetentionStrategy(0).isTerminable()).isFalse();
    assertThat(new MesosRetentionStrategy(5).isTerminable()).isTrue();
  }

  @Test
  public void should_check_back_after_1_minute_when_idle_termination_minutes_is_equals_to_0() {
    // Given
    int idleTerminationMinutes = 0;

    MesosRetentionStrategy mesosRetentionStrategy = new MesosRetentionStrategy(idleTerminationMinutes);

    MesosComputer mesosComputer = PowerMockito.mock(MesosComputer.class);
    MesosSlave mesosSlave = mock(MesosSlave.class);

    when(mesosComputer.getNode()).thenReturn(mesosSlave);
    when(mesosComputer.getConnectTime()).thenReturn(new DateTime().minusMillis(200).getMillis());

    // When
    mesosRetentionStrategy.check(mesosComputer);

    // Then
    verify(mesosComputer, never()).isOffline();
  }

  @Test
  public void should_never_be_automatically_terminated_when_idle_termination_minutes_is_equals_to_0() {
    // Given
    int idleTerminationMinutes = 0;

    MesosRetentionStrategy mesosRetentionStrategy = new MesosRetentionStrategy(idleTerminationMinutes);

    MesosComputer mesosComputer = PowerMockito.mock(MesosComputer.class);
    MesosSlave mesosSlave = mock(MesosSlave.class);

    when(mesosComputer.getNode()).thenReturn(mesosSlave);
    when(mesosComputer.getConnectTime()).thenReturn(new DateTime().minusMinutes(10).getMillis());
    when(mesosComputer.isOffline()).thenReturn(false);
    when(mesosComputer.isIdle()).thenReturn(true);

    // When
    mesosRetentionStrategy.check(mesosComputer);

    // Then
    verify(mesosSlave, never()).terminate();
  }

}
