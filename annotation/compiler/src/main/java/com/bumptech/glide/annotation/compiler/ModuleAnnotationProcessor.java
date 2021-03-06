package com.bumptech.glide.annotation.compiler;

import com.bumptech.glide.annotation.GlideModule;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

// Links in Javadoc will work due to build setup, even though there is no direct dependency here.
/**
 * Generates classes to allow Glide to discover and call all
 * {@link com.bumptech.glide.module.ChildGlideModule}s in an application and any dependent libraries
 * of the application and the RootGlideModule included in the application itself.
 *
 * <p>This processor discovers all {@link com.bumptech.glide.module.RootGlideModule} and
 * {@link com.bumptech.glide.module.ChildGlideModule} implementations that are
 * annotated with {@link com.bumptech.glide.annotation.GlideModule}. Any implementations missing the
 * annotation will be ignored.
 *
 * <p>Two classes are generated by this processor:
 * <ul>
 *   <li>For {@link com.bumptech.glide.module.ChildGlideModule}s - A GlideIndexer class in a
 *      specific package that will later be used by the processor to discover all
 *      {@link com.bumptech.glide.module.ChildGlideModule} classes.
 *   <li>For {@link com.bumptech.glide.module.RootGlideModule}s - A single
 *      {@link com.bumptech.glide.module.RootGlideModule} implementation
 *     ({@link com.bumptech.glide.GeneratedRootGlideModule}) that calls all
 *     {@link com.bumptech.glide.module.ChildGlideModule}s and the
 *     original {@link com.bumptech.glide.module.RootGlideModule} in the correct order when Glide is
 *     initialized.
 * </ul>
 *
 * <p>{@link com.bumptech.glide.module.RootGlideModule} implementations must only be included in
 * applications, not in libraries. There must be exactly one
 * {@link com.bumptech.glide.module.RootGlideModule} implementation per
 * Application. The {@link com.bumptech.glide.module.RootGlideModule} class is
 * used as a signal that all modules have been found and that the final merged
 * {@link com.bumptech.glide.GeneratedRootGlideModule} impl can be created.
 */
@AutoService(Processor.class)
public final class ModuleAnnotationProcessor extends AbstractProcessor {
  static final String INDEXER_NAME_PREFIX = "GlideIndexer_";

  private static final String GLIDE_MODULE_PACKAGE_NAME = "com.bumptech.glide.module";
  private static final String ROOT_GLIDE_MODULE_SIMPLE_NAME = "RootGlideModule";
  private static final String CHILD_GLIDE_MODULE_SIMPLE_NAME = "ChildGlideModule";
  private static final String COMPILER_PACKAGE_NAME =
      ModuleAnnotationProcessor.class.getPackage().getName();
  private static final String GENERATED_ROOT_MODULE_PACKAGE_NAME = "com.bumptech.glide";
  private static final String CHILD_GLIDE_MODULE_QUALIFIED_NAME =
      GLIDE_MODULE_PACKAGE_NAME + "." + CHILD_GLIDE_MODULE_SIMPLE_NAME;
  private static final String ROOT_GLIDE_MODULE_QUALIFIED_NAME =
      GLIDE_MODULE_PACKAGE_NAME + "." + ROOT_GLIDE_MODULE_SIMPLE_NAME;
  private static final boolean DEBUG = false;

  private final List<TypeElement> rootGlideModules = new ArrayList<>();
  private final Set<String> glideModuleClassNames = new HashSet<>();
  private boolean isGeneratedRootGlideModuleWritten;
  private TypeElement childGlideModuleType;
  private TypeElement rootGlideModuleType;
  private int round;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnvironment) {
    super.init(processingEnvironment);
    childGlideModuleType =
        processingEnv.getElementUtils().getTypeElement(CHILD_GLIDE_MODULE_QUALIFIED_NAME);
    rootGlideModuleType =
        processingEnv.getElementUtils().getTypeElement(ROOT_GLIDE_MODULE_QUALIFIED_NAME);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return new HashSet<>(
        Arrays.asList(
            ModuleIndex.class.getName(),
            GlideModule.class.getName()
        )
    );
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.RELEASE_7;
  }

  /**
   * Each round we do the following:
   * <ol>
   *   <li>Find all RootGlideModules and save them to an instance variable (throw if > 1).
   *   <li>Find all ChildGlideModules
   *   <li>For each ChildGlideModule, write an Indexer with an Annotation with the class name.
   *   <li>If we wrote any Indexers, return and wait for the next round.
   *   <li>If we didn't write any Indexers and there is a RootGlideModule, write the
   *   GeneratedRootGlideModule. Once the GeneratedRootGlideModule is written, we expect to be
   *   finished. Any further generation of related classes will result in errors.
   * </ol>
   */
  @Override
  public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
    round++;
    // Order matters here, if we find an Indexer below, we return before writing the root module. If
    // we fail to add to rootModules before then, we might accidentally skip a valid RootModule.
    List<TypeElement> childGlideModules = new ArrayList<>();
    for (TypeElement element : getElementsFor(GlideModule.class, env)) {
      if (processingEnv.getTypeUtils().isAssignable(element.asType(),
          rootGlideModuleType.asType())) {
        rootGlideModules.add(element);
      } else if (processingEnv.getTypeUtils().isAssignable(element.asType(),
          childGlideModuleType.asType())) {
        childGlideModules.add(element);
      } else {
        throw new IllegalStateException("@GlideModule can only be applied to ChildGlideModule"
            + " and RootGlideModule implementations, not: " + element);
      }
    }

    debugLog("got child modules: " + childGlideModules);
    debugLog("got root modules: " + rootGlideModules);

    if (rootGlideModules.size() > 1) {
      throw new IllegalStateException(
          "You cannot have more than one RootGlideModule, found: " + rootGlideModules);
    }

    if (!childGlideModules.isEmpty()) {
      if (isGeneratedRootGlideModuleWritten) {
        throw new IllegalStateException("Cannot process ChildModules after writing RootModules: "
            + childGlideModules);
      }
      TypeSpec indexer = GlideIndexerGenerator.generate(childGlideModules);
      writeIndexer(indexer);

      debugLog("Wrote an Indexer this round, skipping the root module to ensure all indexers are"
          + " found");
       // If I write an Indexer in a round in the target package, then try to find all classes in
      // the target package, my newly written Indexer won't be found. Since we wrote a class with
      // an Annotation handled by this processor, we know we will be called again in the next round
      // and we can safely wait to write our RootModule until then.
      return true;
    }

    // rootGlideModules is added to in order to catch errors where multiple RootGlideModules may be
    // present for a single application or library. Because we only add to rootGlideModules, we use
    // isGeneratedRootGlideModuleWritten to make sure the GeneratedRootGlideModule is written at
    // most once.
    if (isGeneratedRootGlideModuleWritten || rootGlideModules.isEmpty()) {
      return false;
    }

    debugLog("Processing root module: " + rootGlideModules.iterator().next());
    // If this package is null, it means there are no classes with this package name. One way this
    // could happen is if we process an annotation and reach this point without writing something
    // to the package. We do not error check here because that shouldn't happen with the
    // current implementation.
    PackageElement glideGenPackage =
        processingEnv.getElementUtils().getPackageElement(COMPILER_PACKAGE_NAME);
    glideModuleClassNames.addAll(getGlideModuleClassNames(glideGenPackage));

    TypeSpec generatedRootGlideModule =
        RootModuleGenerator.generate(
            processingEnv, rootGlideModules.get(0).getQualifiedName().toString(),
            glideModuleClassNames);
    writeRootModule(generatedRootGlideModule);
    isGeneratedRootGlideModuleWritten = true;

    infoLog("Wrote GeneratedRootGlideModule with: " + glideModuleClassNames);

    return true;
  }

  @SuppressWarnings("unchecked")
  private Set<String> getGlideModuleClassNames(PackageElement glideGenPackage) {
    Set<String> glideModules = new HashSet<>();
    List<? extends Element> glideGeneratedElements = glideGenPackage.getEnclosedElements();
    for (Element indexer : glideGeneratedElements) {
      ModuleIndex annotation = indexer.getAnnotation(ModuleIndex.class);
      // If the annotation is null, it means we've come across another class in the same package
      // that we can safely ignore.
      if (annotation != null) {
        Collections.addAll(glideModules, annotation.glideModules());
      }
    }

    debugLog("Found GlideModules: " + glideModules);
    return glideModules;
  }


  private void writeIndexer(TypeSpec indexer) {
    writeClass(COMPILER_PACKAGE_NAME, indexer);
  }

  private void writeRootModule(TypeSpec rootModule) {
    writeClass(GENERATED_ROOT_MODULE_PACKAGE_NAME, rootModule);
  }

  private void writeClass(String packageName, TypeSpec clazz) {
    try {
      debugLog("Writing class:\n" + clazz);
      JavaFile.builder(packageName, clazz).build().writeTo(processingEnv.getFiler());
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private List<TypeElement> getElementsFor(
      Class<? extends Annotation> clazz, RoundEnvironment env) {
    Collection<? extends Element> annotatedElements = env.getElementsAnnotatedWith(clazz);
    return ElementFilter.typesIn(annotatedElements);
  }

  private void debugLog(String toLog) {
    if (DEBUG) {
      infoLog(toLog);
    }
  }

  private void infoLog(String toLog) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[" + round + "] " + toLog);
  }
}
