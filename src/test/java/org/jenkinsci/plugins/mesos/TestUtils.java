package org.jenkinsci.plugins.mesos;

import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.jvnet.hudson.test.JenkinsRecipe;

// Copied from https://issues.jenkins-ci.org/browse/JENKINS-48466
public class TestUtils {
  public static class JenkinsRule extends org.jvnet.hudson.test.JenkinsRule {
    private final ParameterContext context;

    JenkinsRule(ParameterContext context) {
      this.context = context;
    }

    @Override
    public void recipe() throws Exception {
      Optional<JenkinsRecipe> a = context.findAnnotation(JenkinsRecipe.class);
      if (!a.isPresent()) return;
      final JenkinsRecipe.Runner runner = a.get().value().newInstance();
      recipes.add(runner);
      tearDowns.add(() -> runner.tearDown(this, a.get()));
    }
  }

  public static class JenkinsParameterResolver implements ParameterResolver, AfterEachCallback {
    private static final String key = "jenkins-instance";
    private static final ExtensionContext.Namespace ns =
        ExtensionContext.Namespace.create(JenkinsParameterResolver.class);

    @Override
    public boolean supportsParameter(
        ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
      return parameterContext.getParameter().getType().equals(JenkinsRule.class);
    }

    @Override
    public Object resolveParameter(
        ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
      JenkinsRule instance =
          extensionContext
              .getStore(ns)
              .getOrComputeIfAbsent(
                  key, key -> new JenkinsRule(parameterContext), JenkinsRule.class);
      try {
        instance.before();
        return instance;
      } catch (Throwable t) {
        throw new ParameterResolutionException(t.toString());
      }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
      JenkinsRule rule = context.getStore(ns).remove(key, JenkinsRule.class);
      if (rule != null) rule.after();
    }
  }
}
