package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author shlock
 * @description 登陆拦截器 需要实现 HandlerInterceptor 接口
 * @create 2025/4/6
 */
public class LoginInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * 前置拦截，在Controller执行之前
     * 做登录验证校验
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            //不存在，拦截 设置响应状态吗为401（未授权）
            response.setStatus(401);
            return false;
        }
        // 2. 获取 session 中保存的用户信息
        //2.基于token获取redis中用户
        String key=RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap.isEmpty()){
            //4.不存在则拦截，设置响应状态吗为401（未授权）
            response.setStatus(401);
            return false;
        }
        //5.将查询到的Hash数据转化为UserDTO对象
        UserDTO userDTO=new UserDTO();
        BeanUtil.fillBeanWithMap(userMap,userDTO, false);
        //6.保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.更新token的有效时间，只要用户还在访问我们就需要更新token的存活时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //8.放行
        return true;
    }

    /**
     * 在视图渲染之后，返回给用户之前
     * 业务执行完后，销毁用户信息，避免内存泄露
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 在线程中移除用户信息，避免内存泄漏
        UserHolder.removeUser();
    }
}
