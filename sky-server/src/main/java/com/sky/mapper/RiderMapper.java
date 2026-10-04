package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.RiderPageQueryDTO;
import com.sky.entity.Rider;
import com.sky.vo.OrderVO;
import com.sky.vo.RiderVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RiderMapper {

    /**
     * 找当前可以派单的骑手：账号是启用的【并且】已经上线。
     *
     * 【为什么是 status = 1 且 online = 1，而不是只看 status】
     * 这两个条件管的不是同一件事（见 Rider 实体和接口文档 §2.1）：
     *   status = 1  账号没被管理员停用 —— 别把单派给一个登不进来的人
     *   online = 1  骑手自己按了"上线" —— 别打扰一个已经收工的人
     * 以前这里只按 status 筛，纯粹是因为"上线"和"启用"当时共用一个字段；
     * 现在拆成两列了，只按 status 筛会把所有【离线】的骑手也一起派上单。
     *
     * 【为什么写死条件、不留参数】
     * 派单没有"筛哪个状态"的选择权：条件写死，调用方就没有把筛选条件写歪的余地。
     * 原来的 getStatus(int status) 留了个参数，看着像通用查询，其实只有一个调用方。
     *
     * @return 可派单的骑手；一个都没有时是空列表，不是 null
     */
    @Select("select * from rider where status = 1 and online = 1")
    List<Rider> listAvailable();

    /**
     * 骑手分页查询（管理端 R1）
     *
     * 返回 RiderVO 而不是 Rider：Rider 上有 password，用它会把密码哈希发给前端。
     * SQL 在 RiderMapper.xml 里（显式列名，不用 select *）。
     *
     * @param riderPageQueryDTO
     * @return
     */
    Page<RiderVO> pageQuery(RiderPageQueryDTO riderPageQueryDTO);

    /**
     * 新增骑手（管理端 R2）
     *
     * 【为什么没有标 @AutoFill】
     * AutoFillAspect 是用反射调 setCreateUser(Long) / setUpdateUser(Long) 的，
     * 而 Rider 没有这两个字段（rider 表也没有 create_user / update_user 两列），
     * getMethod 会抛 NoSuchMethodException —— **编译期完全看不出来，一调用就是 500**。
     * 所以 create_time / update_time 只能由 Service 手动赋值。
     *
     * 【列清单里为什么没有 online】
     * 靠数据库的 DEFAULT 0 生效：新建的账号应该是"离线"，等骑手自己上线。
     *
     * ⚠️ 别"顺手"把 online 加进这个列清单 —— 一旦加上，就必须保证 rider.getOnline()
     * 不是 null，否则会报 "Column 'online' cannot be null"。
     * （显式传 NULL 时列的 DEFAULT 是不生效的，本项目在 orders.delivery_status 上踩过一次。）
     * 显式设置 online 是 R9「上线/下线」的事，那时 Rider 实体要先补上这个字段。
     *
     * @param rider
     */
    @Insert("insert into rider (username, password, name, phone, status, create_time, update_time) "
            + "values (#{username}, #{password}, #{name}, #{phone}, #{status}, #{createTime}, #{updateTime})")
    void insert(Rider rider);

    /**
     * 按 id 查骑手（管理端 R3 / R4 判断"这个人存不存在"用）
     *
     * ⚠️ 返回的是实体，里面带着 password 的 MD5。**只能内部用，
     * 绝不能直接塞进 Result 返回给前端** —— /admin/employee/page 那次密码泄漏，
     * 就是因为把实体直接当响应发出去了。
     *
     * @param id
     */
    @Select("select * from rider where id = #{id}")
    Rider getById(Long id);

    /**
     * 编辑骑手（管理端 R3）—— 列清单只有 name / phone / update_time。
     *
     * 【为什么写成写死列名的静态 SQL，而不是动态 <set> + <if>】
     * 动态 SQL 是"传了就改"：只要有人往 RiderDTO 里加字段、或者哪天把 Service 里的
     * builder 改回 BeanUtils.copyProperties，username / status 就会被一起改掉。
     * 静态列清单是白名单，多一列都进不去。
     *
     * 反面教材就在项目里：EmployeeMapper.xml 的 update 是动态的，里面有
     * <if test="username != null">username = #{username},</if>，
     * 所以【员工编辑是能改登录账号的】—— 改完旧账号立刻失效，而且没有任何审计。
     *
     * 【update_time 为什么直接写 now() 而不是 @AutoFill】
     * 切面会反射调 setUpdateUser(Long)，而 Rider 没这个字段，会抛 NoSuchMethodException。
     * 直接 now() 让数据库时钟说了算，也省掉一次 Java 侧赋值。
     *
     * @param rider
     */
    @Update("update rider set name = #{name}, phone = #{phone}, update_time = now() where id = #{id}")
    void update(Rider rider);

    /**
     * 启用 / 停用骑手账号（管理端 R4）—— 只改 status 和 update_time。
     *
     * 【为什么不能复用上面的 update】
     * 那个方法的列清单是写死的 name / phone / update_time（R3 的白名单）。
     * 拿它来改 status 的话，name 和 phone 会被一并写成 null，而 rider.name 是 NOT NULL，
     * 直接报错。这正是"静态列清单"的代价：每个写路径都要有自己的 SQL ——
     * 换来的好处是任何一条路径都不可能顺手改到别的列。
     *
     * 【注意改的是 status（账号启停），不是 online（接单状态）】
     * 两者以前共用一个字段，导致"骑手一下线就登不进自己账号"（登录会因为 status=0
     * 报"账号被锁定"）。现在 status 只由管理员动、online 只由骑手自己动，
     * 所以停用【不影响】online，也不会回收他手上派送中的单。
     *
     * @param rider
     */
    @Update("update rider set status = #{status}, update_time = now() where id = #{id}")
    void updateStatus(Rider rider);

    /**
     * 骑手自己上线 / 下线（R9）—— 只改 online 和 update_time。
     *
     * 【为什么不能复用上面两个 update】
     * update 的列清单写死了 name / phone，updateStatus 写死了 status。
     * 拿它们来改 online 会把别的列一并写成 null：name 是 NOT NULL，会直接报错；
     * status 被写成 null 更隐蔽 —— 登录校验只拦 0，那个骑手照样登得进来，
     * 但派单（要 status = 1）和停用都会失效。静态列清单的代价就是每个写路径
     * 一条 SQL，换来的是任何一条路径都不可能改到别的列。
     *
     * 【为什么这里不用 CAS】
     * 上线 / 下线是"把值设成某个数"，不是状态流转，连点两次都应该成功 ——
     * 加个 online &lt;&gt; #{online} 的条件反而会让第二次点击报"状态错误"。
     * 真正需要 CAS 的是 R8 送达那种"必须从 4 变成 5"的流转。
     *
     * 【为什么用 now() 而不是 @AutoFill】
     * 同 insert / update：切面反射调 setUpdateUser(Long)，Rider 没有这个字段，
     * 运行时抛 NoSuchMethodException（编译期完全看不出来）。
     *
     * @param rider 只会用到 id 和 online 两个字段
     */
    @Update("update rider set online = #{online}, update_time = now() where id = #{id}")
    void updateOnline(Rider rider);

}
