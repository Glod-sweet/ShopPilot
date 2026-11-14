import java.sql.*;

/**
 * 用与 .idea/dataSources.xml 完全相同的 JDBC URL 与驱动，
 * 模拟 DataGrip 的 "Test Connection"，验证配置是否正确。
 *
 * 编译运行见 verify-datasource.sh。
 */
public class VerifyDataSource {

    // 必须与 dataSources.xml 里的 jdbc-url 一字不差
    // 注意 characterEncoding 必须写 UTF-8，不能写 utf8mb4：
    // Connector/J 不认 utf8mb4 这个名字，会抛
    // "Unsupported character encoding 'utf8mb4'"。
    // 写 UTF-8 后驱动会自动使用 MySQL 的 utf8mb4 字符集。
    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/shop_pilot"
            + "?useUnicode=true&characterEncoding=UTF-8"
            + "&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true"
            + "&useSSL=false"
            + "&tinyInt1isBit=false";

    public static void main(String[] args) throws Exception {
        String user = args.length > 0 ? args[0] : "root";
        String pass = args.length > 1 ? args[1] : "1234";

        System.out.println("URL: " + URL);
        System.out.println("User: " + user);
        System.out.println();

        try (Connection conn = DriverManager.getConnection(URL, user, pass)) {
            DatabaseMetaData md = conn.getMetaData();
            System.out.println("[OK] 连接成功");
            System.out.println("  产品      : " + md.getDatabaseProductName()
                    + " " + md.getDatabaseProductVersion());
            System.out.println("  驱动      : " + md.getDriverName()
                    + " " + md.getDriverVersion());
            System.out.println("  当前库    : " + conn.getCatalog());
            System.out.println();

            // 1) 表清单 —— 对应 DataGrip 左侧的 schema 树
            int tableCount = 0;
            StringBuilder tables = new StringBuilder();
            try (ResultSet rs = md.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableCount++;
                    if (tables.length() < 300) {
                        tables.append(rs.getString("TABLE_NAME")).append(" ");
                    }
                }
            }
            System.out.println("  表数量    : " + tableCount);
            System.out.println("  表清单    : " + tables.toString().trim());
            System.out.println();

            // 2) 中文是否正常 —— utf8mb4 生效的关键验证
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, title, price FROM sp_product ORDER BY id LIMIT 3")) {
                System.out.println("  中文商品名验证:");
                while (rs.next()) {
                    System.out.println("    " + rs.getLong("id") + "  "
                            + rs.getString("title") + "  ¥" + rs.getBigDecimal("price"));
                }
            }
            System.out.println();

            // 3) TINYINT(1) 的读取类型 —— 这是与后端对齐的关键
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT on_shelf, gift_wrappable FROM sp_product WHERE id = 200301")) {
                if (rs.next()) {
                    Object onShelf = rs.getObject("on_shelf");
                    System.out.println("  TINYINT(1) 类型验证:");
                    System.out.println("    on_shelf = " + onShelf
                            + "  (Java 类型: " + onShelf.getClass().getSimpleName() + ")");
                    System.out.println("    期望是 Integer/Long 而非 Boolean —— 与后端 tinyInt1isBit=false 一致");
                }
            }
            System.out.println();

            // 4) 场景域能否查到 —— 这是本项目的核心表
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT s.name AS scene, c.name AS category, sc.weight
                         FROM sp_scene_category sc
                         JOIN sp_scene s ON s.id = sc.scene_id
                         JOIN sp_category c ON c.id = sc.category_id
                         WHERE s.code = 'birthday_gift'
                         ORDER BY sc.weight DESC LIMIT 3""")) {
                System.out.println("  场景映射验证（birthday_gift → 品类）:");
                while (rs.next()) {
                    System.out.println("    " + rs.getString("scene")
                            + " → " + rs.getString("category")
                            + " (权重 " + rs.getInt("weight") + ")");
                }
            }

            System.out.println();
            System.out.println("PASS: DataGrip 数据源配置可用");
        } catch (SQLException e) {
            System.out.println("[FAIL] " + e.getMessage());
            System.out.println("  SQLState: " + e.getSQLState() + "  ErrorCode: " + e.getErrorCode());
            System.exit(1);
        }
    }
}
