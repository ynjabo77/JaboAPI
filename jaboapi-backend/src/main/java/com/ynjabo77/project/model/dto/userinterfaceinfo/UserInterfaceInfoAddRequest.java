package com.ynjabo77.project.model.dto.userinterfaceinfo;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建请求
 */
@Data
public class UserInterfaceInfoAddRequest implements Serializable {
    /**
     * 调用用户 id
     */
    private Long userId;

    /**
     * 接口 id
     */
    private Long interfaceInfoId;

    /**
     * 总调用次数
     */
    private Integer totalNum;

    private static final long serialVersionUID = 1L;
}