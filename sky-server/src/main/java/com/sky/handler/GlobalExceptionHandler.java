package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.exception.UserNotLoginException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     *
     * 全项目的统一约定：业务异常返回 HTTP 200 + Result(code=0, msg=xxx)，
     * 前端只弹一个 toast，不清 token、不跳转。绝大多数"某次操作失败"都归这一类。
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 登录态已经失效：token 的签名和有效期都没问题，但它指向的用户已经不存在了
     * （比如账号被删）。这是 getUserInfo 里 user == null 时抛出来的。
     *
     * 【为什么必须单独拎出来回 401，不能跟普通业务异常一起回 200 + code=0】
     * 这两件事前端的处理完全不同：
     *   - HTTP 200 + code=0 ：只弹 toast，token 继续留在 localStorage
     *   - HTTP 401          ：前端的 401 分支会 removeLoginUser() 并跳回登录页
     * 混在业务异常里的话，用户的死 token 会一直躺着，之后每个请求都重复弹同一句话，
     * 除了手动退出没有别的出路 —— 状态不会自愈。
     *
     * @ExceptionHandler 按【最具体的异常类型】匹配，所以这个方法会盖过上面
     * BaseException 那个，两者不冲突。
     */
    @ExceptionHandler(UserNotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result userNotLoginHandler(UserNotLoginException ex){
        log.error("登录态已失效，要求重新登录：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 数据库唯一索引被撞（重复账号等）。
     *
     * ⚠️ 这个方法原来【没有 @ExceptionHandler 注解】，是一段从来没被调用过的死代码 ——
     * 所以数据库拒绝重复时异常没人接，交给 Spring 默认处理，返回 HTTP 500
     * 加一段 Spring 自己的错误 JSON。
     * 实测：新增同名骑手、新增同名员工，两条接口都是 500，而不是约定好的 code=0 + 友好提示。
     *
     * 注意 SQLIntegrityConstraintViolationException extends SQLException，
     * 【不是】BaseException，所以它也落不到上面那个业务异常处理器上，必须单独标。
     */
    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException ex){
        String message = ex.getMessage();
        if(message.contains("Duplicate entry")){
            String[] split = message.split(" ");
            String username = split[2];
            String msg = username + MessageConstant.ALREADY_EXIST;
            return Result.error(msg);
        }else {
            return Result.error(MessageConstant.UNKNOWN_ERROR);
        }
    }

}
