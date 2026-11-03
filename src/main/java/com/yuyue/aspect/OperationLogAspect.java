package com.yuyue.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuyue.common.OperationLog;
import com.yuyue.web.UserContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.regex.Pattern;

/**
 * 操作日志切面：拦截所有标注 {@link OperationLog} 的 Controller 方法，
 * 统一记录操作人、动作、入参摘要、执行结果与耗时，异常时打 WARN 并原样抛出。
 *
 * <p>仅输出到日志文件（不落库）；与 AccessLogInterceptor 的全量 HTTP 访问日志互补：
 * 访问日志回答「谁调了哪个接口」，本切面回答「关键业务动作的具体内容与结果」。
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    /**
     * 入参摘要用的序列化器。
     * <p>注册 JavaTimeModule 才能处理 LocalDate / LocalDateTime——否则带日期的入参
     * （如赛事创建 / 球局创建）会序列化失败，摘要退化成「入参序列化失败」。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** 入参摘要最大长度，避免大对象刷屏 */
    private static final int MAX_ARGS_LENGTH = 500;

    /** 敏感字段脱敏：password / passwordHash / oldPassword / token / 验证码等 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(\"(?:password|passwordHash|oldPassword|token|secret|code)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint pjp, OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        Long userId = UserContext.get();
        String method = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + ((MethodSignature) pjp.getSignature()).getMethod().getName();

        String args = null;
        if (operationLog.logArgs()) {
            args = summarizeArgs(pjp.getArgs());
        }

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[操作日志] userId={} 动作=\"{}\" 方法={} 入参={} 失败={}: {} 耗时={}ms",
                    userId, operationLog.value(), method, args,
                    ex.getClass().getSimpleName(), ex.getMessage(), cost);
            throw ex;
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[操作日志] userId={} 动作=\"{}\" 方法={} 入参={} 结果=成功 耗时={}ms",
                userId, operationLog.value(), method, args, cost);
        return result;
    }

    /**
     * 序列化入参：跳过 Servlet 对象 / MultipartFile（只记文件名与大小），
     * 敏感字段打码，超长截断。
     */
    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        try {
            Object[] view = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                view[i] = describe(args[i]);
            }
            String json = MAPPER.writeValueAsString(view);
            json = SENSITIVE.matcher(json).replaceAll("$1\"***\"");
            if (json.length() > MAX_ARGS_LENGTH) {
                json = json.substring(0, MAX_ARGS_LENGTH) + "...(截断)";
            }
            return json;
        } catch (Exception e) {
            return "<入参序列化失败: " + e.getClass().getSimpleName() + ">";
        }
    }

    private Object describe(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof ServletRequest || arg instanceof ServletResponse) {
            return "<Servlet 对象已忽略>";
        }
        if (arg instanceof MultipartFile mf) {
            return "<文件: " + mf.getOriginalFilename() + ", " + mf.getSize() + "B>";
        }
        if (arg instanceof MultipartFile[] files) {
            return "<文件数组: " + files.length + " 个>";
        }
        return arg;
    }
}
