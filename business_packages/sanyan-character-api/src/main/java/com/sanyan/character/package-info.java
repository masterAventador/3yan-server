/**
 * sanyan-character-api：Character 域对内契约。
 *
 * <p>同 user-api 的约束（java-backend §2.2）：
 * <ul>
 *   <li>只含接口 + DTO + 事件定义</li>
 *   <li>零依赖</li>
 *   <li>实现在 sanyan-character-core，通过 CharacterApiImpl 暴露</li>
 * </ul>
 */
package com.sanyan.character;
