package com.sky.service;

import com.sky.dto.RiderDTO;
import com.sky.dto.RiderOrderPageDTO;
import com.sky.dto.RiderPageQueryDTO;
import com.sky.result.PageResult;

/**
 * 骑手管理（管理端）。
 *
 * 接口清单见 docs/superpowers/specs/2026-09-26-rider-api-design.md §5.1。
 *
 * 目前实现了 R1（分页查询）、R2（新增）、R3（编辑）、R4（启停）。
 * 接口按最终形态立着，后面往里加方法即可，不用再动调用方。
 */
public interface RiderServiceBusiness {

    /**
     * 骑手分页查询
     * @param riderPageQueryDTO
     * @return
     */
    PageResult pageQuery(RiderPageQueryDTO riderPageQueryDTO);

    /**
     * 新增骑手
     * @param riderDTO
     */
    void add(RiderDTO riderDTO);

    /**
     * 编辑骑手
     * @param riderDTO
     */
    void update(RiderDTO riderDTO);

    /**
     * 启用 / 停用骑手账号
     *
     * 方法名和 EmployeeService.startOrStop / CategoryService.startOrStop 保持一致。
     *
     * @param status 1 启用 / 0 停用
     * @param id     骑手 id
     */
    void startOrStop(Integer status, Long id);

}
