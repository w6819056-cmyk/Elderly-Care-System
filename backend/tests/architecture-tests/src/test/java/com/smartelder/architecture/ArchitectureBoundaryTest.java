package com.smartelder.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "com.smartelder")
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule platform_does_not_depend_on_tenant_business = noClasses()
            .that().resideInAPackage("com.smartelder.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.smartelder.core..",
                    "com.smartelder.business..",
                    "com.smartelder.module.."
            );

    @ArchTest
    static final ArchRule api_does_not_depend_on_internal_implementations = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..internal..");

    @Test
    void importedClassModelContainsPlatformAndSharedContracts() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.smartelder");
        classes.contain("com.smartelder.platform.PlatformApplication");
        classes.contain("com.smartelder.common.ApiResponse");
        classes.contain("com.smartelder.module.api.ModuleDescriptor");
    }
}