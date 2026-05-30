package com.sanyan.user.web;

import com.sanyan.user.internal.UserEntity;
import lombok.Data;

/**
 * 第三方登录响应。两种互斥结果，用静态工厂区分，避免一堆 null 传参出错：
 * <ul>
 *   <li>{@link #loggedIn(UserEntity, String)}：第三方身份已绑定账号，直接登录，返回 userId + token；needBind=false。</li>
 *   <li>{@link #needBind(String)}：第三方身份未绑定账号，返回 bindTicket，客户端须走 /oauth/bind-phone；needBind=true。</li>
 *   <li>{@link #needMergeAuth()}：绑定时手机号已注册过账号，需本人证明（验原密码或登录后绑定）；needMergeAuth=true。</li>
 * </ul>
 */
@Data
public class OauthLoginData {

    /** 命中登录时有，未绑时 null。 */
    private Long userId;
    /** 命中登录时有的登录 token，未绑时 null。 */
    private String token;
    private String nickname;
    private String avatar;
    /** true = 未绑账号，需走 /oauth/bind-phone。 */
    private boolean needBind;
    /** needBind=true 时有；命中登录时 null。 */
    private String bindTicket;
    /** true = 该手机号已注册账号，需本人证明（客户端引导验原密码或登录后在设置里绑定）。 */
    private boolean needMergeAuth;

    private OauthLoginData() {
    }

    /** 第三方身份已绑定账号：直接登录。 */
    public static OauthLoginData loggedIn(UserEntity user, String token) {
        OauthLoginData data = new OauthLoginData();
        data.userId = user.getId();
        data.token = token;
        data.nickname = user.getNickname();
        data.avatar = user.getAvatar();
        data.needBind = false;
        return data;
    }

    /** 第三方身份未绑定账号：返回 bindTicket，走绑定流程。 */
    public static OauthLoginData needBind(String bindTicket) {
        OauthLoginData data = new OauthLoginData();
        data.needBind = true;
        data.bindTicket = bindTicket;
        return data;
    }

    /**
     * 该手机号已注册账号、本人证明不通过（缺/错原密码，或目标账号无密码无法本期验证）：
     * 客户端据此引导用户验证账号本人或登录后在设置里绑定。
     */
    public static OauthLoginData needMergeAuth() {
        OauthLoginData data = new OauthLoginData();
        data.needMergeAuth = true;
        return data;
    }
}
