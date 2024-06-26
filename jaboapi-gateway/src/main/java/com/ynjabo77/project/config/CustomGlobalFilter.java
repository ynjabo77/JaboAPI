package com.ynjabo77.project.config;

import com.ynjabo77.jaboapiclientsdk.model.entity.InterfaceInfo;
import com.ynjabo77.jaboapiclientsdk.model.entity.User;
import com.ynjabo77.jaboapiclientsdk.service.InnerInterfaceInfoService;
import com.ynjabo77.jaboapiclientsdk.service.InnerUserInterfaceInfoService;
import com.ynjabo77.jaboapiclientsdk.service.InnerUserService;
import com.ynjabo77.jaboapiclientsdk.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 全局过滤
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @DubboReference
    private InnerUserService innerUserService;

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    private static final String INTERFACE_HOST = "http://localhost:8123";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = INTERFACE_HOST + request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识：" + request.getId());
        log.info("请求路径：" + path);
        log.info("请求方法：" + method);
        log.info("请求参数：" + request.getQueryParams());
        log.info("请求来源地址：" + request.getRemoteAddress());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("请求来源地址：" + sourceAddress);
        ServerHttpResponse response = exchange.getResponse();
        // 2. 访问控制 - 黑白名单
        // if (!IP_WHITE_LIST.contains(sourceAddress)) {
        //     response.setStatusCode(HttpStatus.FORBIDDEN);
        //     return response.setComplete();
        // }
        // 3. 用户鉴权（判断 ak、sk 是否合法）
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String body = null;
        try {
            body = URLDecoder.decode(headers.getFirst("body"), "utf8");
        } catch (UnsupportedEncodingException e) {
            log.error("解密 body 出现错误，异常信息为：" + e);
            return handleInvokeError(response, "系统异常");
        }
        // 数据库中查是否已分配给用户
        User invokeUser = null;
        try {
            invokeUser = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            log.error("远程调用出错 innerUserService.getInvokeUser(accessKey),", e);
        }
        if (invokeUser == null) {
            return handleNoAuth(response, "公钥未分配");
        }
        if (Long.parseLong(nonce) > 10000) {
            return handleNoAuth(response, "随机数过大");
        }
        // 时间戳和当前时间不超过五分钟
        Long currentTime = System.currentTimeMillis() / 1000;// 1s
        final Long FiVE_MINUTES = 60 * 5L;
        if ((currentTime - Long.parseLong(timestamp)) >= FiVE_MINUTES) {
            return handleNoAuth(response, "请求有效时间到期");
        }
        // 数据库中查出 secretKey
        String secretKey = invokeUser.getSecretKey();
        String serverSign = SignUtils.genSign(body, secretKey);
        if (sign == null || !sign.equals(serverSign)) {
            return handleNoAuth(response, "密钥异常");
        }
        // 4. 请求的模拟接口是否存在，以及请求方法是否匹配
        InterfaceInfo interfaceInfo = null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
        } catch (Exception e) {
            log.error("远程调用出错 innerInterfaceInfoService.getInterfaceInfo(path,method)", e);
        }
        if (interfaceInfo == null) {
            return handleNoAuth(response, "接口信息不存在");
        }
        // 是否还有调用次数
        boolean residueCount = innerUserInterfaceInfoService.invokeResidueCount(invokeUser.getId());
        if (!residueCount) {
            return handleNoAuth(response, "没有调用次数");
        }
        // 5. 请求转发，调用模拟接口 + 响应日志
        return handleResponse(exchange, chain, interfaceInfo.getId(), invokeUser.getId());
    }

    /**
     * 处理响应
     *
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 缓存数据的工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 拿到响应码
            HttpStatus statusCode = originalResponse.getStatusCode();
            if (statusCode == HttpStatus.OK) {
                // 装饰，增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    // 等调用完转发的接口后才会执行
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(
                                    fluxBody.map(dataBuffer -> {
                                        // 7. 调用成功，接口调用次数 + 1 invokeCount
                                        try {
                                            innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
                                        } catch (Exception e) {
                                            log.error("远程调用出错innerUserInterfaceInfoService.invoke(interfaceId，userId)", e);
                                        }
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);// 释放掉内存
                                        // 构建日志
                                        StringBuilder sb2 = new StringBuilder(200);
                                        List<Object> rspArgs = new ArrayList<>();
                                        rspArgs.add(originalResponse.getStatusCode());
                                        String data = new String(content, StandardCharsets.UTF_8); // data
                                        sb2.append(data);
                                        // 打印日志
                                        log.info("响应结果：" + data);
                                        return bufferFactory.wrap(content);
                                    }));
                        } else {
                            // 8. 调用失败，返回一个规范的错误码
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 设置 response 对象为装饰过的
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange); // 降级处理返回数据
        } catch (Exception e) {
            log.error("网关处理响应异常" + e);
            return chain.filter(exchange);
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * 响应403
     */
    public Mono<Void> handleNoAuth(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(message.getBytes())));
        // return response.setComplete();
    }

    /**
     * 响应500
     */
    public Mono<Void> handleInvokeError(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(message.getBytes())));
    }
}