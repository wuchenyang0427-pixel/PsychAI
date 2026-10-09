package com.psych.service;

import com.psych.dto.WallMessage;
import com.psych.exception.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 故事集/心声墙：全部数据存 Redis，不引入数据库。
 *
 * 键设计（红线沿用：userId 只来自 token）：
 *   wall:ids                —— 所有心声 ID 的集合
 *   wall:msg:{id}           —— Hash：username / content / likes / authorId / createdAt
 *   wall:liked:{userId}     —— Set：该用户点过赞的心声 ID（防重复点赞）
 *   wall:cooldown:{userId}  —— 发布防刷冷却标记（30 秒自动过期）
 */
@Service
public class WallService {

    private final StringRedisTemplate redis;

    /** 同一用户两次发布心声之间的最小间隔（秒） */
    private static final long PUBLISH_COOLDOWN_SECONDS = 30;

    public WallService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ===== 心声种子数据：{用户名, 内容, 初始点赞数} =====
    // 注意：内容里出现引号一律用「」，ASCII 双引号会截断 Java 字符串导致编译失败
    private static final String[][] SEED = {
            {"来自匿名的用户", "今天终于把拖了很久的辞职信交了，有点怕，但更多的是轻松。", "8"},
            {"来自匿名的用户", "考研失败的第 47 天，我重新翻开了书，这次是为自己读。", "45"},
            {"来自匿名的用户", "谢谢昨晚陪我聊天的咨询师，我今天按时吃了早饭。", "82"},
            {"来自匿名的用户", "和妈妈说出那句「对不起」之后，我们俩在电话里哭了十分钟。", "119"},
            {"来自匿名的用户", "确诊焦虑症不丢人，吃药的第三周，我终于能一觉睡到天亮。", "156"},
            {"来自匿名的用户", "分手三个月，今天路过我们常去的店，没有再难过了。", "193"},
            {"来自匿名的用户", "连续加班 21 天，今天准点下班看到了晚霞，值得记录。", "30"},
            {"来自匿名的用户", "社恐的我今天主动跟新同事打了招呼，她回了我一个微笑。", "67"},
            {"来自匿名的用户", "爸爸第一次在电话里说「爸爸想你了」，我握着手机愣了好久。", "104"},
            {"来自匿名的用户", "没有考上公务员，但我开的小花店今天接到了第一单婚礼花艺。", "141"},
            {"来自匿名的用户", "复诊结果好转，药量减半，医生说可以开始慢慢运动了。", "178"},
            {"来自匿名的用户", "在地铁上给哭泣的陌生人递了纸巾，她下车前对我说谢谢。", "15"},
            {"来自匿名的用户", "暴食症康复的第 100 天，我和食物和解了，也和自己和解了。", "52"},
            {"来自匿名的用户", "今天鼓起勇气预约了第一次心理咨询，还没去，但我已经很勇敢了。", "89"},
            {"来自匿名的用户", "一个人看完了电影、吃了火锅，发现独处原来可以这么自在。", "126"},
            {"来自匿名的用户", "霸凌我的同学聚会道歉了，我说没关系，其实是真的放下了。", "163"},
            {"来自匿名的用户", "拿到驾照啦！焦虑症患者靠 30 次模拟考战胜了路考恐惧。", "200"},
            {"来自匿名的用户", "跟室友把话说开后，他今晚打游戏主动戴上了耳机。", "37"},
            {"来自匿名的用户", "离婚后的第一个生日，我给自己买了蛋糕，蜡烛是我自己吹的。", "74"},
            {"来自匿名的用户", "失业第 60 天收到 offer，原来撑不住的时候，再撑一下就好。", "111"},
            {"来自匿名的用户", "终于拉黑了消耗我三年的人，手机安静了，心里也是。", "148"},
            {"来自匿名的用户", "孩子今天第一次主动说「妈妈我今天在学校很开心」，瞬间泪目。", "185"},
            {"来自匿名的用户", "戒断药物反应最严重的一周过去了，我没有放弃自己。", "22"},
            {"来自匿名的用户", "如果你也正看到这里：深夜会结束的，真的，我替你先走过了。", "59"},
            // ===== 第二批心声（w25 - w44）=====
            {"来自匿名的用户", "今天在地铁上突然就哭了，说不清为什么。但哭完之后好像轻了一点。", "23"},
            {"来自匿名的用户", "我已经连续失眠两周了，脑子一直转个不停。希望今晚能睡个好觉。", "47"},
            {"来自匿名的用户", "考研失败那天我觉得天塌了。现在在便利店打工，反而觉得踏实了一点。", "31"},
            {"来自匿名的用户", "和最好的朋友吵架了，谁都不肯先低头。其实我很想她。", "18"},
            {"来自匿名的用户", "妈妈生病之后，我才发现自己原来这么害怕失去。以前从没想过这种事。", "62"},
            {"来自匿名的用户", "今天第一次去看了心理医生。走进去之前在门口站了十分钟。但我做到了。", "89"},
            {"来自匿名的用户", "工作三年了，还是不知道自己想要什么。看着别人都有方向，很慌。", "41"},
            {"来自匿名的用户", "分手半年了，今天路过我们以前常去的那家店，还是没有勇气走进去。", "35"},
            {"来自匿名的用户", "我爸从来不说爱我，但今天他默默把我爱吃的菜推到了我面前。", "76"},
            {"来自匿名的用户", "在公司被领导当众骂了，回家还要笑着跟家人说今天挺好的。", "54"},
            {"来自匿名的用户", "抑郁最严重的时候，我连刷牙的力气都没有。现在能每天洗个脸了，算进步吧。", "103"},
            {"来自匿名的用户", "高考完那个暑假，我以为人生会从此不一样。后来发现，只是换了一种焦虑。", "29"},
            {"来自匿名的用户", "今天路上有个陌生人对我笑了一下。就一下，但我记了一整天。", "67"},
            {"来自匿名的用户", "我不敢跟任何人说我有多累，怕他们觉得我矫情。", "82"},
            {"来自匿名的用户", "搬来这个城市两年了，还是没有交到一个能随时打电话的朋友。", "38"},
            {"来自匿名的用户", "爷爷走的那天我没哭。后来在超市看到他爱喝的茶，站在货架前哭了很久。", "91"},
            {"来自匿名的用户", "30 岁了，没房没车没对象。但今天给自己做了一顿好吃的，觉得也还行。", "58"},
            {"来自匿名的用户", "我总是把别人的情绪放在自己前面。今天第一次说了「不」，手都在抖。", "72"},
            {"来自匿名的用户", "确诊焦虑症那天，医生说「这不是你的错」。我愣了很久。", "114"},
            {"来自匿名的用户", "不知道未来会怎样，但今晚的月亮很好看。先看完月亮再说吧。", "45"},
            // ===== 第三批心声（w45 - w74）=====
            {"来自匿名的用户", "产后第四个月，第一次在孩子睡着后给自己泡了杯热茶，坐在阳台上发了十分钟呆。", "56"},
            {"来自匿名的用户", "异地恋第三年，我们约定每天睡前打五分钟电话，就五分钟，但从来没断过。", "88"},
            {"来自匿名的用户", "被裁员后瞒着家里假装上班，在图书馆坐了一个月。今天终于敢说出口了。", "95"},
            {"来自匿名的用户", "坚持晨跑三十天，不为减肥，就想证明我还能掌控一件事。", "71"},
            {"来自匿名的用户", "开始写日记了，把不敢说的话都写进去，本子比我有耐心。", "43"},
            {"来自匿名的用户", "领养了一只三条腿的猫，它都不觉得自己可怜，我凭什么觉得我不行。", "167"},
            {"来自匿名的用户", "今天拉黑了家族群里所有催婚的消息，给我妈单独发了句「我过得挺好的」。", "79"},
            {"来自匿名的用户", "三十五岁转行学编程，同学都比我小十岁，但代码不嫌弃我。", "122"},
            {"来自匿名的用户", "考前焦虑到想吐，监考老师递给我一颗薄荷糖，说深呼吸，你准备得够久了。", "64"},
            {"来自匿名的用户", "跟爸爸喝酒，第一次聊起他当年的不容易，爷俩都有点醉，但都听懂了。", "98"},
            {"来自匿名的用户", "手机使用时长从每天九小时降到三小时，多出来的时间我用来发呆和睡觉。", "51"},
            {"来自匿名的用户", "凌晨五点爬起来看日出，冻得直哆嗦，但太阳跳出来的那刻真的值了。", "83"},
            {"来自匿名的用户", "搬进十平米的出租屋，买了一盏暖光的灯，小房间突然就有了家的样子。", "47"},
            {"来自匿名的用户", "给三年前伤害过的朋友发了道歉消息，她回了句「早就不怪你了」。", "106"},
            {"来自匿名的用户", "照镜子的时候不再先挑毛病了，今天对自己说了句「你也挺好看的」。", "73"},
            {"来自匿名的用户", "休学一年后重新回到教室，手心全是汗，但同桌悄悄推过来一块巧克力。", "91"},
            {"来自匿名的用户", "二战上岸了。查成绩的时候手抖得输错三次准考证号。", "189"},
            {"来自匿名的用户", "工作出了个大纰漏，以为要被开除，领导说「谁没犯过错，改就行」。", "66"},
            {"来自匿名的用户", "第一次跟朋友开口说「我最近不太好，能陪陪我吗」，她直接打车过来了。", "118"},
            {"来自匿名的用户", "冥想打卡一百天，情绪来的时候，我学会先数五个呼吸再说话。", "58"},
            {"来自匿名的用户", "今天给妈妈打电话没说事，就说想她了，电话那头沉默了一下，笑了。", "87"},
            {"来自匿名的用户", "心理咨询第十二次，咨询师说我比第一次见面时眼睛里有光了。", "134"},
            {"来自匿名的用户", "下雨天没带伞，索性淋着走回了家，竟然觉得特别痛快。", "42"},
            {"来自匿名的用户", "存款见底的那个月，室友每天「顺便」多做一份饭，我假装没看出来。", "109"},
            {"来自匿名的用户", "把留了五年的长发剪了，理发师问我舍得吗，我说该放下的总要放下。", "77"},
            {"来自匿名的用户", "孩子睡着后和老公坐在地板上吃西瓜，好久没这样安静地说说话了。", "93"},
            {"来自匿名的用户", "面试又挂了，但对方说我的作品集是今天看到最好的，这句话我记下了。", "55"},
            {"来自匿名的用户", "学会跟自己和解的第一步：允许自己今天什么都没干成。", "128"},
            {"来自匿名的用户", "凌晨改完方案，楼下早餐铺刚好开门，老板送了我一个刚出锅的茶叶蛋。", "69"},
            {"来自匿名的用户", "把「我必须坚强」换成了「我可以难过」，肩膀一下子就松了。", "146"},
    };

    /**
     * 增量播种：逐条检查，只插入 Redis 里还不存在的心声。
     * 这样新增种子数据后重启即可补齐，已有点赞数也不会被覆盖。
     */
    @PostConstruct
    public void seedMissing() {
        for (int i = 0; i < SEED.length; i++) {
            String id = "w" + (i + 1);
            if (Boolean.TRUE.equals(redis.hasKey("wall:msg:" + id))) {
                continue; // 已存在：保留真实内容和累积点赞，绝不覆盖
            }
            redis.opsForHash().putAll("wall:msg:" + id, Map.of(
                    "username", SEED[i][0],
                    "content", SEED[i][1],
                    "likes", SEED[i][2]));
            redis.opsForSet().add("wall:ids", id);
        }
        // 自增 ID 计数器：若落后于种子数量则对齐，否则用户发布 INCR 取号会撞上种子占用的 ID
        String curSeq = redis.opsForValue().get("wall:seq");
        if (curSeq == null || Long.parseLong(curSeq) < SEED.length) {
            redis.opsForValue().set("wall:seq", String.valueOf(SEED.length));
        }

        migrateAnonymousNames();
    }

    /**
     * 一次性迁移：把全部种子心声（w1 - w44）的发布者统一改为「来自匿名的用户」。
     * 只改 username 字段，内容和真实点赞数原封不动；用标记键保证只执行一次，
     * 迁移后管理端再编辑用户名不会在重启时被改回去。用户自己发布的心声不在范围内。
     */
    private void migrateAnonymousNames() {
        String marker = "wall:migrate:anon-names-v1";
        if (Boolean.TRUE.equals(redis.hasKey(marker))) {
            return;
        }
        String anon = "来自匿名的用户";
        for (int i = 1; i <= SEED.length; i++) {
            String key = "wall:msg:w" + i;
            Object current = redis.opsForHash().get(key, "username");
            if (current != null && !anon.equals(current.toString())) {
                redis.opsForHash().put(key, "username", anon);
            }
        }
        redis.opsForValue().set(marker, "1");
    }

    /** 拉取全部心声，并标注当前用户是否点过赞 */
    public List<WallMessage> list(String userId) {
        List<String> ids = new ArrayList<>(redis.opsForSet().members("wall:ids"));
        Collections.shuffle(ids); // 后端也洗一次，前端每次进入顺序都不同

        java.util.Set<String> likedIds = redis.opsForSet().members("wall:liked:" + userId);

        List<WallMessage> result = new ArrayList<>();
        for (String id : ids) {
            Map<Object, Object> h = redis.opsForHash().entries("wall:msg:" + id);
            if (h.isEmpty()) {
                continue;
            }
            long likes = Long.parseLong(String.valueOf(h.getOrDefault("likes", "0")));
            // 种子/管理员数据没有 authorId 字段，只有用户自己发布的才可删除
            Object authorId = h.get("authorId");
            boolean owner = authorId != null && userId.equals(authorId.toString());
            result.add(new WallMessage(
                    id,
                    String.valueOf(h.get("username")),
                    String.valueOf(h.get("content")),
                    likes,
                    likedIds.contains(id),
                    owner));
        }
        return result;
    }

    /**
     * 点赞（或幂等重复点击）：SADD 判重 + HINCRBY 原子 +1，天然防并发。
     *
     * @return 最新点赞数
     */
    public long like(String userId, String messageId) {
        if (Boolean.FALSE.equals(redis.hasKey("wall:msg:" + messageId))) {
            throw new IllegalArgumentException("心声不存在");
        }
        // SADD 返回 1 = 首次点赞；0 = 集合里已有，不重复计数
        Long added = redis.opsForSet().add("wall:liked:" + userId, messageId);
        if (added != null && added == 1L) {
            redis.opsForHash().increment("wall:msg:" + messageId, "likes", 1L);
        }
        Object likes = redis.opsForHash().get("wall:msg:" + messageId, "likes");
        return Long.parseLong(String.valueOf(likes));
    }

    // ==================== 管理端 CRUD ====================

    /** 管理端列表：可按用户名/内容关键词过滤，按 ID 倒序（新发布在前） */
    public List<WallMessage> adminList(String keyword) {
        List<String> ids = new ArrayList<>(redis.opsForSet().members("wall:ids"));
        String kw = keyword == null ? "" : keyword.trim();
        List<WallMessage> result = new ArrayList<>();
        for (String id : ids) {
            Map<Object, Object> h = redis.opsForHash().entries("wall:msg:" + id);
            if (h.isEmpty()) {
                continue;
            }
            String username = String.valueOf(h.get("username"));
            String content = String.valueOf(h.get("content"));
            if (!kw.isEmpty() && !username.contains(kw) && !content.contains(kw)) {
                continue;
            }
            long likes = Long.parseLong(String.valueOf(h.getOrDefault("likes", "0")));
            result.add(new WallMessage(id, username, content, likes, false, false));
        }
        result.sort(Comparator.comparingLong(
                (WallMessage m) -> Long.parseLong(m.id().substring(1))).reversed());
        return result;
    }

    /** 新增心声：INCR 取全局唯一 ID，初始点赞 0（管理端播种用，无作者归属） */
    public WallMessage create(String username, String content) {
        long seq = redis.opsForValue().increment("wall:seq");
        String id = "w" + seq;
        String u = username.trim();
        String c = content.trim();
        redis.opsForHash().putAll("wall:msg:" + id, Map.of(
                "username", u, "content", c, "likes", "0"));
        redis.opsForSet().add("wall:ids", id);
        return new WallMessage(id, u, c, 0, false, false);
    }

    /**
     * 用户发布心声：真实落库。
     * 1）SET NX EX 做 30 秒防刷冷却，冷却期内直接拒绝；
     * 2）INCR 取全局唯一 ID，Hash 里额外记录 authorId / createdAt，供本人删除校验；
     * 3）昵称为空时兜底为「匿名」。
     */
    public WallMessage publish(String authorId, String username, String content) {
        String cooldownKey = "wall:cooldown:" + authorId;
        Boolean allowed = redis.opsForValue().setIfAbsent(
                cooldownKey, "1", Duration.ofSeconds(PUBLISH_COOLDOWN_SECONDS));
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BizException("发得太频繁啦，歇 30 秒再发吧");
        }

        long seq = redis.opsForValue().increment("wall:seq");
        String id = "w" + seq;
        String u = (username == null || username.trim().isEmpty()) ? "匿名" : username.trim();
        String c = content.trim();
        redis.opsForHash().putAll("wall:msg:" + id, Map.of(
                "username", u,
                "content", c,
                "likes", "0",
                "authorId", authorId,
                "createdAt", String.valueOf(System.currentTimeMillis())));
        redis.opsForSet().add("wall:ids", id);
        return new WallMessage(id, u, c, 0, false, true);
    }

    /** 编辑用户名/内容；只更新这两个字段，点赞数保持不变 */
    public void update(String id, String username, String content) {
        if (Boolean.FALSE.equals(redis.hasKey("wall:msg:" + id))) {
            throw new IllegalArgumentException("心声不存在");
        }
        redis.opsForHash().putAll("wall:msg:" + id, Map.of(
                "username", username.trim(), "content", content.trim()));
    }

    /** 删除心声：删内容、移出索引，并清掉所有用户对它的点赞记录，避免脏数据 */
    public void delete(String id) {
        if (Boolean.FALSE.equals(redis.hasKey("wall:msg:" + id))) {
            throw new IllegalArgumentException("心声不存在");
        }
        doDelete(id);
    }

    /**
     * 用户删除自己发布的心声：先比对 Hash 里的 authorId。
     * 种子数据和管理员发布的数据没有 authorId，任何普通用户都不能删。
     */
    public void deleteByAuthor(String authorId, String id) {
        String key = "wall:msg:" + id;
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            throw new BizException("心声不存在");
        }
        Object author = redis.opsForHash().get(key, "authorId");
        if (author == null || !authorId.equals(author.toString())) {
            throw new BizException("只能删除自己发布的心声");
        }
        doDelete(id);
    }

    /** 真正执行删除：内容 + 索引 + 所有用户对该心声的点赞记录 */
    private void doDelete(String id) {
        redis.delete("wall:msg:" + id);
        redis.opsForSet().remove("wall:ids", id);
        // 学习项目数据量小，keys 可接受；数据量大时应换成 SCAN
        Set<String> likedKeys = redis.keys("wall:liked:*");
        if (likedKeys != null) {
            likedKeys.forEach(k -> redis.opsForSet().remove(k, id));
        }
    }
}
