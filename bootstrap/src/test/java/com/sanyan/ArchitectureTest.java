package com.sanyan;

import com.sanyan.common.error.ErrCode;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.sanyan")
class ArchitectureTest {

    // R1: foundation 不能反向 import business
    @ArchTest
    static final ArchRule foundation_cannot_depend_on_business =
        noClasses().that().resideInAPackage("com.sanyan.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.chat..",
                "com.sanyan.character..",
                "com.sanyan.llm..");

    // R2: 业务领域间不能引彼此的 web/ws 包（4 条独立规则）
    @ArchTest
    static final ArchRule user_must_not_depend_on_other_domain_web_or_ws =
        noClasses().that().resideInAPackage("com.sanyan.user..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.chat.web..", "com.sanyan.chat.ws..",
                "com.sanyan.character.web..", "com.sanyan.character.ws..",
                "com.sanyan.llm.web..", "com.sanyan.llm.ws..");

    @ArchTest
    static final ArchRule chat_must_not_depend_on_other_domain_web_or_ws =
        noClasses().that().resideInAPackage("com.sanyan.chat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.user.web..",
                "com.sanyan.character.web..", "com.sanyan.character.ws..",
                "com.sanyan.llm.web..", "com.sanyan.llm.ws..");

    @ArchTest
    static final ArchRule character_must_not_depend_on_other_domain_web_or_ws =
        noClasses().that().resideInAPackage("com.sanyan.character..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.user.web..",
                "com.sanyan.chat.web..", "com.sanyan.chat.ws..",
                "com.sanyan.llm.web..", "com.sanyan.llm.ws..");

    @ArchTest
    static final ArchRule llm_must_not_depend_on_other_domain_web_or_ws =
        noClasses().that().resideInAPackage("com.sanyan.llm..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.user.web..",
                "com.sanyan.chat.web..", "com.sanyan.chat.ws..",
                "com.sanyan.character.web..");

    // R3: web/ws 层不能直接依赖 Repository（强制走 service）
    @ArchTest
    static final ArchRule web_layer_must_not_access_repositories =
        noClasses().that().resideInAnyPackage("..web..", "..ws..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    // R4: ErrCode enum 必须放 internal 或 common.error 包
    @ArchTest
    static final ArchRule errcode_enums_must_be_in_internal_or_common_error =
        classes().that().implement(ErrCode.class).and().areEnums()
            .should().resideInAnyPackage("..internal..", "com.sanyan.common.error..");
}
