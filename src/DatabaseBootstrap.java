import java.nio.file.*;
import java.sql.*;

public final class DatabaseBootstrap {
    public static void main(String[] args) throws Exception {
        String user = System.getenv("PM_DB_USER");
        String pass = System.getenv("PM_DB_PASS");
        if (user == null || pass == null) throw new IllegalStateException("Missing MySQL credentials");
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=8000";
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement s = c.createStatement()) {
            String sql = Files.readString(Path.of("database.sql"));
            for (String statement : sql.split(";")) {
                String query = statement.trim();
                if (!query.isEmpty()) s.execute(query);
            }
        }
        System.out.println("Database schema verified successfully.");
    }
}
