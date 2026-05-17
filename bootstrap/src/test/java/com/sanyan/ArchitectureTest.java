package com.sanyan;

import com.sanyan.common.error.ErrCode;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * S3 Phase 7 升级：
 *
 * <p><b>删除的旧规则</b>："业务域不依赖其他域的 web/ws 包" —— 已被 Maven 模块边界物理隔离冗余
 * （chat/web、chat/ws 现在物理隔离在 sanyan-chat-core 模块里，其他域不依赖这个模块就根本看不见这些类）。
 *
 * <p><b>新增的规则</b>："任何业务域不允许 import 其他业务域的 internal/ 包"
 * —— java-backend.md §3.3 的代码层守护，与 §4 一层 Maven Enforcer（-core 互斥）形成纵深防御。
 *
 * <p><b>保留的规则</b>：foundation 不能反向 import business、web/ws 不绕过 service 直访 Repository、
 * ErrCode enum 位置约束。
 *
 * <p><b>豁免范围</b>：bootstrap 装配层 test code（e.g. {@code Plan2EndToEndIT}）允许直接
 * import 任何业务域 internal/——这是装配层的合法行为，规则的 source 侧只锁 5 个业务域的代码，
 * bootstrap 不在这 5 个包路径里所以自动豁免。
 */
@AnalyzeClasses(packages = "com.sanyan")
class ArchitectureTest {

    // ============================================================
    // R1: foundation 不能反向 import business（保留）
    // ============================================================
    @ArchTest
    static final ArchRule foundation_cannot_depend_on_business =
        noClasses().that().resideInAPackage("com.sanyan.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.character..",
                "com.sanyan.chat..",
                "com.sanyan.llm..",
                "com.sanyan.embedding..",
                "com.sanyan.memory..");

    // ============================================================
    // R2-R7: 6 个业务域 internal/ 互斥规则
    // 每条规则禁止"其他 5 个业务域"的代码访问本域的 internal/。
    // 同业务域内 -api/、-core/web/、-core/api/ 等访问本域 internal/ 是允许的。
    // bootstrap 装配层在 com.sanyan 顶层包，不在 5 个业务域子包里，自动豁免。
    // ============================================================

    /** user.internal 只能被 user 域内代码访问（其他 5 域不允许 import）。 */
    @ArchTest
    static final ArchRule user_internal_only_accessed_within_user_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.character..",
                "com.sanyan.chat..",
                "com.sanyan.llm..",
                "com.sanyan.embedding..",
                "com.sanyan.memory..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.user.internal..");

    /** character.internal 只能被 character 域内代码访问。 */
    @ArchTest
    static final ArchRule character_internal_only_accessed_within_character_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.chat..",
                "com.sanyan.llm..",
                "com.sanyan.embedding..",
                "com.sanyan.memory..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.character.internal..");

    /** chat.internal 只能被 chat 域内代码访问。 */
    @ArchTest
    static final ArchRule chat_internal_only_accessed_within_chat_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.character..",
                "com.sanyan.llm..",
                "com.sanyan.embedding..",
                "com.sanyan.memory..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.chat.internal..");

    /** llm.internal 只能被 llm 域内代码访问。 */
    @ArchTest
    static final ArchRule llm_internal_only_accessed_within_llm_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.character..",
                "com.sanyan.chat..",
                "com.sanyan.embedding..",
                "com.sanyan.memory..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.llm.internal..");

    /** embedding.internal 只能被 embedding 域内代码访问。 */
    @ArchTest
    static final ArchRule embedding_internal_only_accessed_within_embedding_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.character..",
                "com.sanyan.chat..",
                "com.sanyan.llm..",
                "com.sanyan.memory..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.embedding.internal..");

    /** memory.internal 只能被 memory 域内代码访问。 */
    @ArchTest
    static final ArchRule memory_internal_only_accessed_within_memory_domain =
        noClasses().that().resideInAnyPackage(
                "com.sanyan.user..",
                "com.sanyan.character..",
                "com.sanyan.chat..",
                "com.sanyan.llm..",
                "com.sanyan.embedding..")
            .should().dependOnClassesThat().resideInAPackage("com.sanyan.memory.internal..");

    // ============================================================
    // R8: web/ws 层不能直接依赖 Repository（强制走 service，保留）
    // ============================================================
    @ArchTest
    static final ArchRule web_layer_must_not_access_repositories =
        noClasses().that().resideInAnyPackage("..web..", "..ws..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    // ============================================================
    // R9: ErrCode enum 必须放 internal/ 或 common.error/ 包（保留）
    // ============================================================
    @ArchTest
    static final ArchRule errcode_enums_must_be_in_internal_or_common_error =
        classes().that().implement(ErrCode.class).and().areEnums()
            .should().resideInAnyPackage("..internal..", "com.sanyan.common.error..");
}
