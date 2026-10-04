package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.RiderDTO;
import com.sky.dto.RiderPageQueryDTO;
import com.sky.entity.Rider;
import com.sky.exception.RiderBusinessException;
import com.sky.mapper.LoginMapper;
import com.sky.mapper.RiderMapper;
import com.sky.result.PageResult;
import com.sky.service.RiderServiceBusiness;
import com.sky.vo.RiderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 骑手管理（管理端）实现。
 */
@Service
@Slf4j
public class RiderServiceImplBusiness implements RiderServiceBusiness {

    public static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private RiderMapper riderMapper;


    /**
     * 用来做账号跨表查重。
     * 它那条 UNION 查询和统一登录用的是同一条，理由见 add 的注释。
     */
    @Autowired
    private LoginMapper loginMapper;

    /**
     * 骑手分页查询
     *
     * 【上界：50 条，而且边界是 > 不是 >=】
     * 管理端另外四个分页接口（employee / category / dish / setmeal）目前都不限，
     * 那是历史遗留；骑手是新写的，直接按安全的那侧来。
     * 写成 > 而不是 >= 是为了让"最大 50 条"这个语义准确 —— pageSize=50 应当放行。
     *
     * 异常特意用 RiderBusinessException 而不是 OrderBusinessException：
     * 后者是订单专用的，拿来抛"页的记录量太大"名不副实。
     * 两者都继承 BaseException，所以都会被 GlobalExceptionHandler 兜住，
     * 返回 HTTP 200 + code=0（业务失败不走 4xx/5xx，是本项目的约定）。
     *
     * 【下界：兜底，不报错】
     * page / pageSize 是 int 不是 Integer，不传就是 0 —— 属于"没填"而不是"填错了"，
     * 所以给默认值（第 1 页、10 条）而不是抛异常。
     * 不兜底直接交给 PageHelper 的话，page=0 或 pageSize=0 会得到
     * "total=4 但 records 为空"这种让人以为数据坏了的结果。
     * （顺带确认过：PageHelper 默认 pageSizeZero=false，pageSize=0 会拼出 LIMIT 0
     *   而不是"查全部"，所以这里不存在"用 0 绕过上限"的漏洞。）
     *
     * 兜底放在上界校验之后：一个管上界、一个管下界，互不交叉，顺序不影响结果。
     */
    @Override
    public PageResult pageQuery(RiderPageQueryDTO riderPageQueryDTO) {
        if (riderPageQueryDTO.getPageSize() > MAX_PAGE_SIZE){
            throw new RiderBusinessException(MessageConstant.PAGE_SIZE_TOO_BIG);
        }

        if (riderPageQueryDTO.getPage() < 1) riderPageQueryDTO.setPage(1);

        if (riderPageQueryDTO.getPageSize() < 1) riderPageQueryDTO.setPageSize(10);

        // PageHelper 只对紧接着的这一条查询生效
        PageHelper.startPage(riderPageQueryDTO.getPage(), riderPageQueryDTO.getPageSize());

        Page<RiderVO> page = riderMapper.pageQuery(riderPageQueryDTO);

        long total = page.getTotal();                 // 总记录数
        List<RiderVO> records = page.getResult();     // 当前页数据集合
        return new PageResult(total, records);
    }

    /**
     * 新增骑手
     *
     * 【为什么必须先查三张表，不能只靠 rider 表的唯一索引】
     * 统一登录是一条 UNION ALL 查 employee / rider / user，重名会命中 ≥2 行，
     * LoginServiceImpl 直接拒绝登录（"该账号在多端重复，请联系管理员"）。
     * 也就是说，一旦建出一个和员工重名的骑手，**那个账号从此谁都登不进去**，
     * 管理端和骑手端一起废掉 —— 而 rider 表上的 uk_rider_username 只保证表内唯一，
     * 管不到跨表，所以这一层必须自己做。
     * @param riderDTO
     */
    @Override
    public void add(RiderDTO riderDTO) {
        String username = riderDTO.getUsername();

        // 账号必填：rider.username 是 NOT NULL，缺了会撞数据库约束，然后落到
        // GlobalExceptionHandler 里 SQLIntegrityConstraintViolationException 的 else 分支，
        // 前端只看到一句"未知错误"（实测：body 传 {} 就是 code=0 +"未知错误"）。
        if (!StringUtils.hasText(username)) {
            throw new RiderBusinessException(MessageConstant.ACCOUNT_REQUIRED);
        }
        // name / phone 的校验和编辑（R3）共用同一份实现，别在两处各写一遍 —— 会分叉
        validateNameAndPhone(riderDTO);

        // 账号必须跨三张表唯一，否则登录时会因为重名被拒 —— 详见方法注释
        if (!loginMapper.listByUsername(username).isEmpty()) {
            throw new RiderBusinessException(MessageConstant.ACCOUNT_ALREADY_EXIST);
        }

        Rider rider = Rider.builder()
                .username(username)
                .name(riderDTO.getName())
                .phone(riderDTO.getPhone())
                // 密码固定用默认密码的 MD5（和 employee 表一致；表单不让管理员填，所以 RiderDTO 里也没这个字段）
                .password(DigestUtils.md5DigestAsHex(PasswordConstant.DEFAULT_PASSWORD.getBytes()))
                // 新建的账号一律启用。能不能接单是另一回事（online），那是 R9 的事
                .status(StatusConstant.ENABLE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        riderMapper.insert(rider);
        log.info("新增骑手：{}({})", rider.getName(), rider.getUsername());
    }

    /**
     * 编辑骑手（R3）—— 只改 name / phone。
     *
     * 【为什么 username / status / online 改不了】
     * 不是靠"记得别传"，而是两道结构性的防线：
     *   1. 下面用 builder 只赋 id / name / phone（不 copyProperties）
     *   2. RiderMapper.update 是静态 SQL，列清单里根本没有那三列
     * 少任何一道都可能出事。项目里的员工是一道都没有：EmployeeServiceImpl.update 用
     * copyProperties 拷整个 DTO，EmployeeMapper.xml 的 update 又是动态 <set>，
     * 于是【员工编辑是能改登录账号的】—— 改完旧账号立刻失效，而且没有任何审计记录。
     *
     * 【为什么先 getById 查一次，而不是靠 update 的影响行数判断"存在"】
     * MySQL 的 affected-rows 有两套语义：Connector/J 默认 useAffectedRows=false 时返回
     * "匹配行数"，所以"值一个字都没改"也会返回 1；而 useAffectedRows=true 时返回
     * "真正变更行数"，那种情况下值没变就是 0，会被误判成"骑手不存在"。
     * 先查一次就不依赖驱动的默认值，代价只是一次主键查询。
     * （这条我没法用 CLI 实测验证 —— mysql 客户端和 JDBC 的 affected-rows 语义本来就不同，
     *   所以这里选的是"不依赖它"的写法。）
     *
     * 【为什么改手机号不联动改 username】
     * username 是登录凭据，改它等于换账号（旧账号立刻失效），那属于账号迁移，
     * 应该有审计和通知，不该混在"编辑资料"里。所以这里刻意不联动 —— 这是决定，不是漏了。
     * 代价是会出现"账号 13800000001、手机号 13800000009"这种状态，可以接受。
     *
     * 【为什么不做手机号唯一校验】
     * rider.phone 没有唯一索引，employee.phone 也没有 —— 保持一致。
     * 已知并接受"两个骑手可以同手机号"。
     *
     * 【为什么不管骑手是不是停用】
     * 编辑资料和账号启停（R4）是两件事，停用的骑手照样能改资料。
     *
     * @param riderDTO
     */
    @Override
    public void update(RiderDTO riderDTO) {
        validateNameAndPhone(riderDTO);

        // 先确认这个骑手存在，再更新 —— 见方法注释里关于 affected-rows 的说明。
        // id 为 null 时这次查询也查不到，会走同一个分支报"骑手不存在" ✓
        if (riderMapper.getById(riderDTO.getId()) == null) {
            throw new RiderBusinessException(MessageConstant.RIDER_NOT_EXIST);
        }

        Rider rider = Rider.builder()
                .id(riderDTO.getId())
                .name(riderDTO.getName())
                .phone(riderDTO.getPhone())
                .build();

        riderMapper.update(rider);
        log.info("编辑骑手：id={}，name={}", rider.getId(), rider.getName());
    }

    /**
     * name / phone 的必填与格式校验 —— 新增（R2）和编辑（R3）共用。
     *
     * 【为什么必须抽出来共用，而不是两边各写一遍】
     * 各写一遍的话，以后改规则（放宽手机号位数、或者手机号改成可选）必然漏改一处，
     * 两个接口的校验口径就分叉了。
     * 这一路反复踩的就是这个：加购和下单取了不同的价格来源、
     * addShoppingCart 和 submitOrder 对"商品可不可售"的判法不同 —— 全是分叉出来的。
     *
     * @param riderDTO
     */
    private void validateNameAndPhone(RiderDTO riderDTO) {
        if (!StringUtils.hasText(riderDTO.getName())
                || !StringUtils.hasText(riderDTO.getPhone())) {
            throw new RiderBusinessException(MessageConstant.RIDER_NAME_PHONE_REQUIRED);
        }
        // phone 列是 varchar(11)，超长会报 "Data too long"，同样是"未知错误"，提前拦掉
        if (!riderDTO.getPhone().trim().matches("\\d{11}")) {
            throw new RiderBusinessException(MessageConstant.PHONE_FORMAT_ERROR);
        }
    }

    /**
     * 启用 / 停用骑手账号（R4）
     *
     * 【这是"账号级"操作，不是"上线/下线"，两者别混】
     *   status —— 账号启停（1 启用 / 0 停用），管理员改；LoginServiceImpl 登录时校验的就是它
     *   online —— 接单状态（1 上线 / 0 离线），骑手自己改（R9）；派单挑人时看它
     * 两者以前共用一个字段，后果是"骑手点一下下线，就再也登不进自己账号"
     * （登录会因为 status=0 报"账号被锁定"，而他改回来必须先能登录）。
     * 所以这里【只动 status，绝不碰 online】。
     *
     * 【停用之后会发生什么】
     *   · 该骑手立刻无法【新】登录（LoginServiceImpl 拦 status=0，报"账号被锁定"）
     *   · 已签发的 token 在过期前仍然有效 —— JWT 无状态的固有特性，本期接受
     *   · 派单不会再挑到他（OrderServiceimplBusiness.deliveryOrder 筛的就是 status = 1）
     *   · 手上"派送中"的单【不回收】，他仍要送完 —— 改派是独立需求，本期不做
     *
     * 【为什么校验 status 只能是 0 / 1】
     * 员工和分类的启停接口都没校验，传个 5 进去也照写。那会造出一个
     * "登得了录、却永远派不到单"的骑手：登录只拦 0、派单只挑 1，其它值两边都不管。
     * 这里刻意比员工那套严一点。
     *
     * 【为什么不复用 update】
     * update 的 SQL 列清单写死了 name / phone（R3 的白名单），拿它来改 status 会把
     * name 写成 null，而 rider.name 是 NOT NULL，直接报错。所以有专用的 updateStatus。
     *
     * @param status 1 启用 / 0 停用
     * @param id     骑手 id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        // 用 equals 而不是 ==：status 是 Integer 包装类型，== 比的是引用
        // （0 / 1 命中了 Integer 缓存，恰好不会出错，但那是巧合，不是保证）
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new RiderBusinessException(MessageConstant.RIDER_STATUS_INVALID);
        }

        // 先确认骑手存在：否则 update 影响 0 行，调用方会以为改成功了。
        // id 为 null 时这次查询也查不到，走同一个分支 ✓
        if (riderMapper.getById(id) == null) {
            throw new RiderBusinessException(MessageConstant.RIDER_NOT_EXIST);
        }

        Rider rider = Rider.builder()
                .id(id)
                .status(status)
                .build();

        riderMapper.updateStatus(rider);
        log.info("{}骑手账号：id={}", StatusConstant.ENABLE.equals(status) ? "启用" : "停用", id);
    }

}
