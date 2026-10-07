package com.sky.constant;

/**
 * 信息提示常量类
 */
public class MessageConstant {

    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String ACCOUNT_LOCKED = "账号被锁定";
    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String USER_NOT_LOGIN = "用户未登录";
    public static final String CATEGORY_BE_RELATED_BY_SETMEAL = "当前分类关联了套餐,不能删除";
    public static final String CATEGORY_BE_RELATED_BY_DISH = "当前分类关联了菜品,不能删除";
    public static final String SHOPPING_CART_IS_NULL = "购物车数据为空，不能下单";
    public static final String ADDRESS_BOOK_IS_NULL = "用户地址为空，不能下单";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String SETMEAL_ENABLE_FAILED = "套餐内包含未启售菜品，无法启售";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";
    public static final String DISH_ON_SALE = "起售中的菜品不能删除";
    public static final String SETMEAL_ON_SALE = "起售中的套餐不能删除";
    public static final String DISH_BE_RELATED_BY_SETMEAL = "当前菜品关联了套餐,不能删除";
    public static final String ORDER_STATUS_ERROR = "订单状态错误";
    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String ALREADY_EXIST = "用户已存在";
    public static final String REFUND_FAILED = "退款失败，请稍后重试";
    public static final String REFUND_NOT_CONFIGURED = "订单已取消，退款功能未启用，请人工处理退款";
    public static final String RIDER_NOT_FOUND = "暂无可用骑手，请稍后再试";
    public static final String ACCOUNT_CONFLICT = "该账号在多端重复，请联系管理员";
    public static final String PACK_AMOUNT_ERROR = "打包费不合理";
    public static final String PAGE_SIZE_TOO_BIG = "页的记录量太大";
    public static final String ORDER_ALREADY_CANCELLED = "订单已被取消";
    public static final String GOODS_NOT_AVAILABLE = "商品不存在或已下架";
    public static final String SHOPPING_CART_PARAM_ERROR = "请指定要操作的菜品或套餐";
    public static final String GOODS_INVALID_TIP = "%s 已失效，请从购物车中移除后再下单";

    /**
     * 新增账号时发现账号名已被占用。
     * 比 ALREADY_EXIST（"用户已存在"）准确 —— 骑手/员工是"账号"不是"用户"，
     * 而且查重是跨 employee / rider / user 三张表做的，不一定是哪张表里已有。
     */
    public static final String ACCOUNT_ALREADY_EXIST = "该账号已存在";

    /**
     * 新增账号时账号名没给。
     * 不提前拦的话会撞数据库的 NOT NULL 约束，最后落到
     * GlobalExceptionHandler 里 SQLIntegrityConstraintViolationException 的 else 分支，
     * 前端只看到一句"未知错误"。
     */
    public static final String ACCOUNT_REQUIRED = "账号不能为空";

    /**
     * 新增 / 编辑骑手时姓名或手机号没给。
     * 和 ACCOUNT_REQUIRED 分开，是因为编辑（R3）根本没有"账号"这个入参 ——
     * 复用同一句话会让前端摸不着头脑（提示里提了个它没传的字段）。
     */
    public static final String RIDER_NAME_PHONE_REQUIRED = "姓名和手机号都不能为空";

    /**
     * 编辑骑手时找不到这个骑手。
     * ⚠️ 千万别复用 RIDER_NOT_FOUND —— 那句是"暂无可用骑手，请稍后再试"，
     * 用于派单时挑不到人，跟"编辑一个不存在的骑手"完全不是一回事。
     */
    public static final String RIDER_NOT_EXIST = "骑手不存在";

    /**
     * 启停骑手账号时传了 0 / 1 之外的值。
     * 不拦的话会造出一个"登得了录、但永远派不到单"的骑手：
     * 登录只拦 status=0，派单只挑 status=1，其它值两边都不管。
     */
    public static final String RIDER_STATUS_INVALID = "骑手账号状态只能是 1（启用）或 0（停用）";

    /** 手机号格式。和前端 AddressPicker 的提示语保持一致 */
    public static final String PHONE_FORMAT_ERROR = "手机号必须是 11 位数字";
    public static final String RIDER_ORDER_STATUS_INVALID = "订单状态只能是派送中/已完成/已取消";

    /**
     * 骑手上线 / 下线的入参不是 0 / 1。
     * 和 RIDER_STATUS_INVALID 分开写：那句说的是"账号启停"（管理员动的手），
     * 这句说的是"接单状态"（骑手自己的动作）—— 两件事现在各占一个字段，
     * 提示语混用会让骑手以为自己的账号被停用了。
     */
    public static final String RIDER_ONLINE_STATUS_INVALID = "接单状态只能是 1（上线）或 0（离线）";
    public static final String REVIEW_ALREADY_EXISTS   = "这单已经评价过了";
    public static final String REVIEW_SCORE_INVALID    = "打分要在 1 到 5 星之间";
    public static final String REVIEW_CONTENT_TOO_LONG = "评价内容太长了";
    public static final String ORDER_NOT_COMPLETED     = "只能评价已完成的订单";
    public static final String REVIEW_INDEX_CREATE_FAILED = "检索索引创建失败，请检查 ES 连接";
    public static final String REVIEW_INDEX_SWITCH_FAILED = "检索索引切换失败，线上数据未受影响";
    /** ES 的元数据查询（_cat / _count）没查出来：不能猜，直接中止 */
    public static final String REVIEW_INDEX_QUERY_FAILED = "查询 ES 索引信息失败，请检查 ES 连接（本次未改动索引与别名）";
    /** 一条都没写进去、旧索引却有数据：拒绝切别名，保住线上查询 */
    public static final String REVIEW_INDEX_EMPTY_ABORT = "本次重建未写入任何文档，已中止（拒绝切换到空索引）：旧索引与线上查询未受影响";
    /**
     * 本批 _bulk 有条目失败：中止且绝不切别名。
     * 两个 %d 分别是【本次重建真实写入成功的条数】【本批失败的条数】—— 必须是逐条统计出来的数字，
     * 不能再是"失败数 = 整批条数"（I2：那样 indexed + failed 对不上总数，也看不出到底写进去几条）。
     * 用 String.format 填充，与 GOODS_INVALID_TIP 同一个用法。
     */
    public static final String REVIEW_INDEX_BULK_FAILED = "本次重建已写入 %d 条、本批失败 %d 条，已中止：索引未切换，线上数据未受影响";
    /**
     * 进程内互斥（R4）：已有一次重建在进行。
     * 并发重建会让先完成者的清扫逻辑把后者【正在灌的索引】当成残留删掉；
     * 所以拿不到锁时立刻失败，而不是排队等待（管理端手动触发的运维接口，等下去没有意义）。
     */
    public static final String REVIEW_INDEX_BUSY = "另一次索引重建正在进行，请稍后再试";
}
