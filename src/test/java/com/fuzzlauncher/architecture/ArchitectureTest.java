package com.fuzzlauncher.architecture;

import com.fuzzlauncher.application.model.LauncherInfo;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** These rules apply to production bytecode, including constructor and method-body dependencies. */
final class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter().importUrl(LauncherInfo.class.getProtectionDomain().getCodeSource().getLocation());

    @Test
    void domainUsesOnlyDomainAndJdk() {
        classes().that().resideInAPackage("com.fuzzlauncher.domain..").should().onlyDependOnClassesThat()
            .resideInAnyPackage("com.fuzzlauncher.domain..", "java..").check(PRODUCTION);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrPresentation() {
        classes().that().resideInAPackage("com.fuzzlauncher.application..").should().onlyDependOnClassesThat()
            .resideInAnyPackage("com.fuzzlauncher.application..", "com.fuzzlauncher.domain..", "java..").check(PRODUCTION);
    }

    @Test
    void adaptersDoNotDependOnPresentationOrBootstrap() {
        noClasses().that().resideInAPackage("com.fuzzlauncher.infrastructure..").should().dependOnClassesThat()
            .resideInAnyPackage("com.fuzzlauncher.ui..", "com.fuzzlauncher.bootstrap..", "com.fuzzlauncher").check(PRODUCTION);
    }

    @Test
    void presentationUsesPortsInsteadOfConcreteAdapters() {
        noClasses().that().resideInAPackage("com.fuzzlauncher.ui..").should().dependOnClassesThat()
            .resideInAnyPackage("com.fuzzlauncher.infrastructure..", "com.fuzzlauncher.bootstrap..", "com.fuzzlauncher").check(PRODUCTION);
    }

    @Test
    void viewModelsDoNotDependOnPagesOrControls() {
        classes().that().resideInAPackage("com.fuzzlauncher.ui.viewmodel..").should().onlyDependOnClassesThat()
            .resideInAnyPackage("com.fuzzlauncher.ui.viewmodel..", "com.fuzzlauncher.ui.task..", "com.fuzzlauncher.application..",
                "com.fuzzlauncher.domain..", "java..", "javafx..")
            .check(PRODUCTION);
    }

    @Test
    void packagesHaveNoDependencyCycles() {
        slices().matching("com.fuzzlauncher.(**)").should().beFreeOfCycles().check(PRODUCTION);
    }
}
