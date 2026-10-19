package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /**
     * 图片存放根目录。
     *
     * <p>原实现引用 {@code SystemConstants.IMAGE_UPLOAD_DIR}，那是个硬编码的
     * Windows 绝对路径（{@code D:\tlias\...}），在 macOS / Linux 上目录不存在，
     * 每次上传都会 IOException → 500。现在改为可配置，默认在当前工作目录下。
     */
    @Value("${hmdp.upload.image-dir:" + SystemConstants.DEFAULT_IMAGE_UPLOAD_DIR + "}")
    private String imageUploadDir;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);

            // 必须用绝对路径。
            // MultipartFile.transferTo(File) 对**相对路径**会交给 Servlet 容器解析——
            // 实际落到 Tomcat 的临时工作目录（.../work/Tomcat/localhost/ROOT/...），
            // 而这个目录下并不存在 blogs/x/y 子目录，于是抛 FileNotFoundException。
            File target = new File(resolveUploadRoot(), fileName).getAbsoluteFile();
            FileUtil.mkParentDirs(target);

            image.transferTo(target);
            log.debug("文件上传成功，{} -> {}", fileName, target.getAbsolutePath());
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(resolveUploadRoot(), filename).getAbsoluteFile();
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    /**
     * 把配置的目录解析成绝对路径。
     *
     * <p>相对路径基于**进程工作目录**解析，而不是 Servlet 容器的临时目录——
     * 后者是 {@code MultipartFile.transferTo} 对相对路径的默认行为，会导致文件落到
     * {@code .../work/Tomcat/localhost/ROOT/} 下。
     */
    private File resolveUploadRoot() {
        return new File(imageUploadDir).getAbsoluteFile();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名（目录由调用方在写入前创建）
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

}
