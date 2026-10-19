package com.hmdp.utils;

public class SystemConstants {
    /**
     * 图片上传目录。
     *
     * <p>原值是 {@code "D:\\tlias\\nginx-1.18.0 dianping\\html\\hmdp\\imgs"} —— 课程环境里的
     * Windows 绝对路径。在 macOS / Linux 上这个路径不存在，{@code File.transferTo}
     * 会抛 IOException，**任何一次图片上传都返回 500**。
     *
     * <p>现在改为由配置项 {@code hmdp.upload.image-dir} 提供，默认值
     * {@link #DEFAULT_IMAGE_UPLOAD_DIR} 是当前工作目录下的 {@code uploads/imgs}，
     * 跨平台可用。上传前会自动创建目录（见 {@code UploadController}）。
     */
    public static final String DEFAULT_IMAGE_UPLOAD_DIR = "uploads/imgs";

    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
