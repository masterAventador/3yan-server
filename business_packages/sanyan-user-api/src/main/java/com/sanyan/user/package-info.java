/**
 * sanyan-user-api：User 域对内契约。
 *
 * <p>规则约束（java-backend §2.2）：
 * <ul>
 *   <li>本模块只能含 接口 + DTO + 事件定义，不能有任何 @Service / @Component / @Entity</li>
 *   <li>本模块零依赖（不引其他业务模块也不引基础模块）</li>
 *   <li>实现在 sanyan-user-core 内，通过 UserApiImpl 暴露</li>
 * </ul>
 */
package com.sanyan.user;
