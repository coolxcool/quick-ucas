package cn.keepfight;

import org.apache.commons.io.FileUtils;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * UCAS 中国科学院大学选课、抢课 Java 脚本
 * Created by 卓建欢 on 2018/1/8.
 */
public class Main {
    /**
     * 共享 Cookies
     */
    private Map<String, String> cookies;

    /**
     * 使用可重入锁以免发生临界区阻塞而死掉
     */
    private ReentrantLock lock = new ReentrantLock();
    private Pattern pattern_identity = Pattern.compile("http://jwxk\\.ucas\\.ac\\.cn/login\\?Identity=([0-9A-Za-z\\-]+)");
    private Pattern pattern_manage = Pattern.compile("/courseManage/selectCourse\\?s=([0-9A-Za-z\\-]+)");
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private ExecutorService service = Executors.newCachedThreadPool();

    private Properties nameAndPsw = new Properties();
    private List<String> courseList;

    /**
     * 教务网登录用 ID
     */
    private String manage_id;

    /**
     * 选课用的学生 ID
     */
    private String student_id;

    public static void main(String[] args) throws Exception {
        new Main().run();
    }

    /**
     * 选课运行实例：
     * <li>
     *     <li>获得选课用的学生 ID</li>
     *     <li>提交想选的课程</li>
     * </li>
     */
    public void run() throws Exception {
        getPswAndList();
        getID();
        courseList.forEach(this::submit);
    }

    /**
     * 加载 /info.properties 文件获得学生账号密码信息
     * 加载 /courseList.txt 文件获得选课列表
     */
    private void getPswAndList() throws Exception {
        // 加载配置文件
        nameAndPsw.load(new FileReader("info.properties"));
        // 检查配置文件正确性
        if (nameAndPsw.getProperty("userName")==null || nameAndPsw.getProperty("pwd")==null){
            throw new Exception("/info.properties 文件配置错误！");
        }

        // 获取课程列表
        courseList = FileUtils.readLines(new File("courseList.txt")).stream()
                .filter(s->s.matches("[0-9]{6}"))
                .collect(Collectors.toList());
    }

    /**
     * 注册指定课程编码（选课操作）
     *
     * @param sid 课程编码
     */
    private void submit(String sid) {
        service.submit(() -> {
            int count = 1;
            // 为了保证选到课，选到课之后也不返回
            while (true) {
                try {
                    if (!save(sid, count)) {
                        count++;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Thread.sleep(1000);
            }
        });
    }

    /**
     * 运行整个登录的流程。以获得 Student_id，这个 id 用于选课的唯一标识
     *
     * 这是适用于 session_ID 失效而需要重新登录
     */
    private void getID() throws Exception {
        login();
        identity();
        identity_login();
        manage();
    }

    /**
     * 获得登录 Cookies
     *
     * 这里的登录只适用于校园网内部的登录，可以免验证码，校园网外部的可以查看另外一个 github 项目
     */
    public void login() throws Exception {
        Connection cnt = Jsoup.connect("http://sep.ucas.ac.cn/slogin");
        Map<String, String> datas = new HashMap<>();
        datas.put("userName", nameAndPsw.getProperty("userName"));
        datas.put("pwd", nameAndPsw.getProperty("pwd"));
        datas.put("sb", "sb");
        cnt.data(datas);
        Connection.Response resp = cnt.execute();
        cookies = resp.cookies();
    }

    /**
     * 获取登录教务网用的 ID
     */
    public void identity() throws Exception {
        Connection cnt = Jsoup.connect("http://sep.ucas.ac.cn/portal/site/226/821");
        cnt.cookies(cookies);
        Connection.Response resp = cnt.execute();
        cookies.putAll(resp.cookies());
        Matcher m = pattern_identity.matcher(resp.body());
        if (!m.find()) {
            System.out.println(resp.body());
            throw new Exception("manage_id not found");
        }
        manage_id = m.group(1);
        System.out.println("manage_id:" + manage_id);
        if (manage_id == null) {
            System.out.println(resp.body());
            throw new Exception("manage_id not found");
        }
    }

    /**
     * 登录教务网
     */
    public void identity_login() throws Exception {
        Connection cnt = Jsoup.connect("http://jwxk.ucas.ac.cn/login?Identity=" + manage_id);
        cnt.cookies(cookies);
        Connection.Response resp = cnt.execute();
        cookies.putAll(resp.cookies());
        if (!resp.body().contains("学生角色")) {
            throw new Exception("identity_login error");
        }
    }

    /**
     * 获取选课用学生 ID
     */
    public void manage() throws Exception {
        Connection cnt = Jsoup.connect("http://jwxk.ucas.ac.cn/courseManage/main");
        cnt.cookies(cookies);
        Connection.Response resp = cnt.execute();
        cookies.putAll(resp.cookies());
        Matcher m = pattern_manage.matcher(resp.body());
        if (!m.find()) {
            throw new Exception("student_id not found");
        }
        student_id = m.group(1);
        System.out.println("student_id:" + student_id);
    }

    /**
     * 操作 URL 请求操作
     *
     * @param sid   课程标识 ID
     * @param count 当前操作计数
     * @return 选课成功或者选课在时间上发生冲突返回 true，其他情况返回 false
     */
    public boolean save(String sid, int count) throws Exception {
        lock.lock();
        try {
            String time = LocalDateTime.now().format(formatter);
            Connection cnt = Jsoup.connect("http://jwxk.ucas.ac.cn/courseManage/saveCourse");
            cnt.cookies(cookies);
            Map<String, String> datas = new HashMap<>();
            datas.put("sids", sid);
            datas.put("s", student_id);
            cnt.data(datas);
            Connection.Response resp = cnt.execute();
            cookies.putAll(resp.cookies());
            String body = resp.body();

            // 选课操作结果分析
            if (body.contains("选课成功")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "选课成功...");
                return true;
            } else if (body.contains("重新登录")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "重新登录...");
                getID();
                return false;
            } else if (body.contains("超过限选人数")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "捡漏中...");
                return false;
            } else if (body.contains("当前时间不在选课有效时间内")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "当前时间不在选课有效时间内...");
                return false;
            } else if (body.contains("未开通选课权限")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "未开通选课权限...");
                return false;
            } else if (body.contains("上课时间冲突")) {
                System.out.println("sid:[" + sid + "]\tcount:[" + count + "]\ttime:[" + time + "]:" + "上课时间冲突...");
                return true;
            }
            System.out.println(body);
            return false;
        } finally {
            lock.unlock();
        }
    }

}
