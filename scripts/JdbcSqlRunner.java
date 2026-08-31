import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

/**
 * 极简 SQL 迁移执行器，只接受环境变量中的数据库凭据，避免把密码写进脚本或命令历史。
 *
 * 用法：java --class-path mysql-connector.jar scripts/JdbcSqlRunner.java sql/migration.sql
 */
public class JdbcSqlRunner {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: JdbcSqlRunner <sql-file>");
        }
        String url = requiredEnv("VIDEOAI_JDBC_URL");
        String username = requiredEnv("VIDEOAI_JDBC_USERNAME");
        String password = requiredEnv("VIDEOAI_JDBC_PASSWORD");
        String sql = Files.readString(Path.of(args[0]));

        String[] statements = Arrays.stream(sql.split(";"))
                .map(JdbcSqlRunner::removeCommentLines)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (int index = 0; index < statements.length; index++) {
                statement.execute(statements[index]);
                System.out.printf("Executed statement %d/%d%n", index + 1, statements.length);
            }
        }
    }

    private static String removeCommentLines(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
