package com.hmdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 批量生成压测用的登录 token。
 *
 * <p>产出的 {@code src/test/resources/tokens.txt} 每行一个 token，
 * 供 {@code scripts/seckill-loadtest.py --token-file} 或 JMeter 的 CSV Data Set 使用。
 *
 * <p>原实现有三个 bug，导致它从未成功产出过 token：
 * <ol>
 *   <li>{@code result.getData().toString()} 必 NPE —— {@code sendCode} 返回的是
 *       {@code Result.ok()}，没有 data，验证码只存在 Redis 里。</li>
 *   <li>用 {@code "\\src\\main\\resources"} 拼路径。反斜杠在 macOS/Linux 上是合法
 *       文件名字符，所以它不会报错，而是默默创建了一个名叫
 *       {@code src\main\resources\tokens.txt} 的怪文件。</li>
 *   <li>{@code countDownLatch.countDown()} 只在成功分支调用。线程一抛异常，
 *       计数永远归不了零，{@code await()} 就永久挂死 —— 测试既不通过也不失败，
 *       只是卡住。这是前两个 bug 没被及时发现的原因。</li>
 * </ol>
 *
 * <p>前置条件：MySQL / Redis 已启动，且 {@code tb_user} 里有用户数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoucherOrderControllerTest {

    /** 取多少个用户来生成 token。要与压测的并发用户数匹配 */
    private static final int USER_LIMIT = 1000;

    /** 并发度。登录是 I/O 密集，不需要一个用户一个线程 */
    private static final int CONCURRENCY = 64;

    /** 输出到 test resources，不污染 main resources（后者会被打进 jar） */
    private static final Path TOKEN_FILE = Path.of("src", "test", "resources", "tokens.txt");

    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;

    @Resource
    private ObjectMapper mapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("批量登录并导出 token 到 src/test/resources/tokens.txt")
    void login() throws Exception {
        List<String> phones = userService.lambdaQuery()
                .select(User::getPhone)
                .last("limit " + USER_LIMIT)
                .list()
                .stream()
                .map(User::getPhone)
                .collect(Collectors.toList());

        if (phones.isEmpty()) {
            throw new IllegalStateException("tb_user 没有数据，先导入 db/hmdp.sql");
        }

        List<String> tokens = new CopyOnWriteArrayList<>();
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(phones.size());
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        for (String phone : phones) {
            pool.execute(() -> {
                try {
                    tokens.add(login(phone));
                } catch (Exception e) {
                    failures.add(phone + " -> " + e.getMessage());
                } finally {
                    // 必须放在 finally：否则一个线程失败就会让 await() 永久挂死
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.MINUTES);
        pool.shutdown();

        if (!finished) {
            throw new IllegalStateException(
                    String.format("超时：%d/%d 个登录任务未完成", latch.getCount(), phones.size()));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "%d/%d 个用户登录失败，前 5 条：%s",
                    failures.size(), phones.size(),
                    failures.stream().limit(5).collect(Collectors.joining("; "))));
        }

        Files.createDirectories(TOKEN_FILE.getParent());
        Files.write(TOKEN_FILE, tokens, StandardCharsets.UTF_8);
        System.out.printf("已写入 %d 个 token 到 %s%n", tokens.size(), TOKEN_FILE.toAbsolutePath());
    }

    /** 走一遍真实的登录流程：请求验证码 -> 从 Redis 取码 -> 登录换 token */
    private String login(String phone) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/user/code").queryParam("phone", phone))
                .andExpect(MockMvcResultMatchers.status().isOk());

        // 验证码只写在 Redis 和日志里，HTTP 响应体里没有（Result.ok() 不带 data）
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (code == null) {
            throw new IllegalStateException("Redis 中取不到验证码，key="
                    + RedisConstants.LOGIN_CODE_KEY + phone);
        }

        LoginFormDTO form = LoginFormDTO.builder().phone(phone).code(code).build();
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/user/login")
                        .content(mapper.writeValueAsString(form))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result result = mapper.readerFor(Result.class).readValue(body);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException("登录失败：" + result.getErrorMsg());
        }
        return result.getData().toString();
    }
}
