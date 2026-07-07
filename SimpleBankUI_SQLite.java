import javax.swing.*;
import java.awt.*;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SimpleBankUI_SQLite - Same app as SimpleBankUI.java, but backed by a real
 * SQLite database instead of an in-memory HashMap. Data now survives
 * between runs (it's saved to a file called "bank.db" next to this program).
 *
 * WHAT CHANGED FROM THE IN-MEMORY VERSION:
 *   - A new DatabaseHelper class handles all SQL (create tables, insert,
 *     update, select). The Swing/UI code barely changes at all - it just
 *     calls DatabaseHelper methods instead of touching a HashMap.
 *   - User now has an "id" (the database's primary key) instead of being
 *     looked up by a HashMap key.
 *   - Transaction history is stored in its own "transactions" table and
 *     queried when you open the History screen, instead of living in a
 *     Java List in memory.
 *
 * REQUIRED SETUP - this needs the SQLite JDBC driver on your classpath:
 *   1. Download the driver jar (one file, no installation needed):
 *      https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar
 *   2. Put it in the same folder as this .java file.
 *   3. Compile:
 *        javac -cp sqlite-jdbc-3.46.1.0.jar SimpleBankUI_SQLite.java
 *   4. Run:
 *        java -cp .:sqlite-jdbc-3.46.1.0.jar SimpleBankUI_SQLite        (Mac/Linux)
 *        java -cp .;sqlite-jdbc-3.46.1.0.jar SimpleBankUI_SQLite        (Windows)
 *
 * A file named bank.db will appear in the folder the first time you run it -
 * that's your whole database, viewable with any SQLite browser tool.
 */
public class SimpleBankUI_SQLite extends JFrame {

    // ---- Currently logged-in user ----
    private User currentUser;

    // ---- Layout manager that lets us switch between panels/screens ----
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainPanel = new JPanel(cardLayout);

    // Screen name constants
    private static final String LOGIN_SCREEN = "LOGIN";
    private static final String REGISTER_SCREEN = "REGISTER";
    private static final String DASHBOARD_SCREEN = "DASHBOARD";
    private static final String HISTORY_SCREEN = "HISTORY";

    private JLabel balanceLabel;
    private JLabel welcomeLabel;
    private DefaultListModel<String> historyListModel;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    public SimpleBankUI_SQLite() {
        super("Simple Bank (SQLite)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(420, 320);
        setLocationRelativeTo(null);
        setResizable(false);

        DatabaseHelper.initDatabase();

        mainPanel.add(buildLoginScreen(), LOGIN_SCREEN);
        mainPanel.add(buildRegisterScreen(), REGISTER_SCREEN);
        mainPanel.add(buildDashboardScreen(), DASHBOARD_SCREEN);
        mainPanel.add(buildHistoryScreen(), HISTORY_SCREEN);

        add(mainPanel);
        cardLayout.show(mainPanel, LOGIN_SCREEN);
    }

    // ------------------------------------------------------------------
    // Simple data classes
    // ------------------------------------------------------------------
    private static class User {
        int id;
        String name;
        String email;
        String pin;
        double balance;
    }

    private static class Transaction {
        String type;
        double amount;
        String timestamp; // stored as ISO text in the DB

        @Override
        public String toString() {
            String sign = type.equals("Deposit") ? "+" : "-";
            String displayTime = timestamp;
            try {
                displayTime = LocalDateTime.parse(timestamp).format(TIME_FORMAT);
            } catch (Exception ignored) {
                // If parsing fails for any reason, fall back to the raw stored text.
            }
            return String.format("%s   %s$%.2f   %s", displayTime, sign, amount, type);
        }
    }

    // ------------------------------------------------------------------
    // DATABASE HELPER - all SQL lives here
    // ------------------------------------------------------------------
    private static class DatabaseHelper {
        private static final String DB_URL = "jdbc:sqlite:bank.db";

        /** Opens a fresh connection. SQLite connections are cheap to open/close per operation. */
        private static Connection connect() throws SQLException {
            return DriverManager.getConnection(DB_URL);
        }

        /** Creates the users and transactions tables if they don't already exist. */
        static void initDatabase() {
            // sqlite-jdbc extracts a native library file to a temp folder when it
            // loads. On some locked-down machines (school/lab computers) the
            // default system temp folder isn't writable, which makes the driver
            // silently fail to register - showing up as "No suitable driver
            // found" instead of a clear permissions error. Pointing it at the
            // current folder instead avoids that.
            System.setProperty("org.sqlite.tmpdir", System.getProperty("user.dir"));

            // Explicitly load the driver class. Modern JDBC drivers normally
            // auto-register themselves, but forcing this here avoids
            // "No suitable driver found" errors caused by classpath quirks.
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                JOptionPane.showMessageDialog(null,
                        "The SQLite driver jar was not found on the classpath.\n" +
                        "Make sure sqlite-jdbc-3.46.1.0.jar is in this folder and\n" +
                        "included in your -cp argument when running the program.",
                        "Missing Driver", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }

            String usersTable =
                    "CREATE TABLE IF NOT EXISTS users (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  name TEXT NOT NULL," +
                    "  email TEXT NOT NULL UNIQUE," +
                    "  pin TEXT NOT NULL," +
                    "  balance REAL NOT NULL DEFAULT 0" +
                    ")";
            String transactionsTable =
                    "CREATE TABLE IF NOT EXISTS transactions (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  type TEXT NOT NULL," +
                    "  amount REAL NOT NULL," +
                    "  timestamp TEXT NOT NULL," +
                    "  FOREIGN KEY(user_id) REFERENCES users(id)" +
                    ")";
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(usersTable);
                stmt.execute(transactionsTable);
            } catch (SQLException e) {
                // A beginner-friendly fatal error dialog, since there's no point
                // continuing if the database can't even set itself up.
                e.printStackTrace(); // full details in the console/terminal
                JOptionPane.showMessageDialog(null,
                        "Could not set up the database:\n" + e.getMessage(),
                        "Database Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        }

        static boolean emailExists(String email) throws SQLException {
            String sql = "SELECT 1 FROM users WHERE email = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        static void insertUser(String name, String email, String pin) throws SQLException {
            String sql = "INSERT INTO users(name, email, pin, balance) VALUES (?, ?, ?, 0)";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, pin);
                ps.executeUpdate();
            }
        }

        /** Returns the matching user, or null if the email/PIN combination doesn't match. */
        static User login(String email, String pin) throws SQLException {
            String sql = "SELECT id, name, email, pin, balance FROM users WHERE email = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;           // no account with that email
                    if (!rs.getString("pin").equals(pin)) return null; // wrong PIN

                    User user = new User();
                    user.id = rs.getInt("id");
                    user.name = rs.getString("name");
                    user.email = rs.getString("email");
                    user.pin = rs.getString("pin");
                    user.balance = rs.getDouble("balance");
                    return user;
                }
            }
        }

        static void updateBalance(int userId, double newBalance) throws SQLException {
            String sql = "UPDATE users SET balance = ? WHERE id = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, newBalance);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
        }

        static void insertTransaction(int userId, String type, double amount) throws SQLException {
            String sql = "INSERT INTO transactions(user_id, type, amount, timestamp) VALUES (?, ?, ?, ?)";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                ps.setString(2, type);
                ps.setDouble(3, amount);
                ps.setString(4, LocalDateTime.now().toString());
                ps.executeUpdate();
            }
        }

        static List<Transaction> getHistory(int userId) throws SQLException {
            String sql = "SELECT type, amount, timestamp FROM transactions " +
                         "WHERE user_id = ? ORDER BY id DESC";
            List<Transaction> results = new ArrayList<>();
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Transaction t = new Transaction();
                        t.type = rs.getString("type");
                        t.amount = rs.getDouble("amount");
                        t.timestamp = rs.getString("timestamp");
                        results.add(t);
                    }
                }
            }
            return results;
        }
    }

    // ------------------------------------------------------------------
    // LOGIN SCREEN
    // ------------------------------------------------------------------
    private JPanel buildLoginScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Login");
        title.setFont(new Font("SansSerif", Font.BOLD, 20));

        JLabel emailLabel = new JLabel("Email:");
        JTextField emailField = new JTextField(18);

        JLabel pinLabel = new JLabel("PIN:");
        JPasswordField pinField = new JPasswordField(18);

        JButton loginButton = new JButton("Login");
        JButton goRegisterButton = new JButton("Create an account");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(emailField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(pinLabel, gbc);
        gbc.gridx = 1; panel.add(pinField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(loginButton, gbc);

        gbc.gridy = 4;
        panel.add(goRegisterButton, gbc);

        gbc.gridy = 5;
        panel.add(messageLabel, gbc);

        loginButton.addActionListener(e -> {
            String email = emailField.getText().trim();
            String pin = new String(pinField.getPassword());

            try {
                User user = DatabaseHelper.login(email, pin);
                if (user == null) {
                    messageLabel.setText("Incorrect email or PIN.");
                } else {
                    currentUser = user;
                    messageLabel.setText(" ");
                    emailField.setText("");
                    pinField.setText("");
                    refreshDashboard();
                    cardLayout.show(mainPanel, DASHBOARD_SCREEN);
                }
            } catch (SQLException ex) {
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        goRegisterButton.addActionListener(e -> {
            messageLabel.setText(" ");
            cardLayout.show(mainPanel, REGISTER_SCREEN);
        });

        return panel;
    }

    // ------------------------------------------------------------------
    // REGISTER SCREEN
    // ------------------------------------------------------------------
    private JPanel buildRegisterScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Create Account");
        title.setFont(new Font("SansSerif", Font.BOLD, 20));

        JLabel nameLabel = new JLabel("Name:");
        JTextField nameField = new JTextField(18);

        JLabel emailLabel = new JLabel("Email:");
        JTextField emailField = new JTextField(18);

        JLabel pinLabel = new JLabel("PIN (4+ digits):");
        JPasswordField pinField = new JPasswordField(18);

        JButton registerButton = new JButton("Register");
        JButton backButton = new JButton("Back to login");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(nameLabel, gbc);
        gbc.gridx = 1; panel.add(nameField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(emailField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; panel.add(pinLabel, gbc);
        gbc.gridx = 1; panel.add(pinField, gbc);

        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(registerButton, gbc);

        gbc.gridy = 5;
        panel.add(backButton, gbc);

        gbc.gridy = 6;
        panel.add(messageLabel, gbc);

        registerButton.addActionListener(e -> {
            String name = nameField.getText().trim();
            String email = emailField.getText().trim();
            String pin = new String(pinField.getPassword());

            if (name.isEmpty() || email.isEmpty() || pin.isEmpty()) {
                messageLabel.setText("Please fill in every field.");
                return;
            }
            if (!email.contains("@") || !email.contains(".")) {
                messageLabel.setText("Please enter a valid email.");
                return;
            }
            if (!pin.matches("\\d{4,}")) {
                messageLabel.setText("PIN must be at least 4 digits.");
                return;
            }

            try {
                if (DatabaseHelper.emailExists(email)) {
                    messageLabel.setText("An account with that email already exists.");
                    return;
                }
                DatabaseHelper.insertUser(name, email, pin);
                messageLabel.setForeground(new Color(0, 128, 0));
                messageLabel.setText("Account created! You can log in now.");
                nameField.setText("");
                emailField.setText("");
                pinField.setText("");
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            cardLayout.show(mainPanel, LOGIN_SCREEN);
        });

        return panel;
    }

    // ------------------------------------------------------------------
    // DASHBOARD SCREEN
    // ------------------------------------------------------------------
    private JPanel buildDashboardScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        welcomeLabel = new JLabel("Welcome!");
        welcomeLabel.setFont(new Font("SansSerif", Font.BOLD, 18));

        balanceLabel = new JLabel("Balance: $0.00");
        balanceLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));

        JTextField amountField = new JTextField(10);
        JButton depositButton = new JButton("Deposit");
        JButton withdrawButton = new JButton("Withdraw");
        JButton historyButton = new JButton("Transaction History");
        JButton logoutButton = new JButton("Logout");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(welcomeLabel, gbc);

        gbc.gridy = 1;
        panel.add(balanceLabel, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 2; gbc.gridx = 0;
        panel.add(new JLabel("Amount:"), gbc);
        gbc.gridx = 1;
        panel.add(amountField, gbc);

        gbc.gridy = 3; gbc.gridx = 0;
        panel.add(depositButton, gbc);
        gbc.gridx = 1;
        panel.add(withdrawButton, gbc);

        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(historyButton, gbc);

        gbc.gridy = 5;
        panel.add(logoutButton, gbc);

        gbc.gridy = 6;
        panel.add(messageLabel, gbc);

        depositButton.addActionListener(e -> {
            Double amount = parseAmount(amountField.getText(), messageLabel);
            if (amount == null) return;
            try {
                double newBalance = currentUser.balance + amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Deposit", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(new Color(0, 128, 0));
                messageLabel.setText("Deposited $" + String.format("%.2f", amount));
                amountField.setText("");
                refreshDashboard();
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        withdrawButton.addActionListener(e -> {
            Double amount = parseAmount(amountField.getText(), messageLabel);
            if (amount == null) return;
            if (amount > currentUser.balance) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Insufficient balance.");
                return;
            }
            try {
                double newBalance = currentUser.balance - amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Withdraw", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(new Color(0, 128, 0));
                messageLabel.setText("Withdrew $" + String.format("%.2f", amount));
                amountField.setText("");
                refreshDashboard();
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        historyButton.addActionListener(e -> {
            refreshHistoryList();
            cardLayout.show(mainPanel, HISTORY_SCREEN);
        });

        logoutButton.addActionListener(e -> {
            currentUser = null;
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            amountField.setText("");
            cardLayout.show(mainPanel, LOGIN_SCREEN);
        });

        return panel;
    }

    // ------------------------------------------------------------------
    // TRANSACTION HISTORY SCREEN
    // ------------------------------------------------------------------
    private JPanel buildHistoryScreen() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Transaction History", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 18));

        historyListModel = new DefaultListModel<>();
        JList<String> historyList = new JList<>(historyListModel);
        historyList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(historyList);

        JButton backButton = new JButton("Back to Dashboard");
        backButton.addActionListener(e -> cardLayout.show(mainPanel, DASHBOARD_SCREEN));

        panel.add(title, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(backButton, BorderLayout.SOUTH);

        return panel;
    }

    /** Queries the database for the current user's transactions and refreshes the list on screen. */
    private void refreshHistoryList() {
        historyListModel.clear();
        if (currentUser == null) return;

        try {
            List<Transaction> history = DatabaseHelper.getHistory(currentUser.id);
            if (history.isEmpty()) {
                historyListModel.addElement("No transactions yet.");
            } else {
                for (Transaction t : history) {
                    historyListModel.addElement(t.toString());
                }
            }
        } catch (SQLException ex) {
            historyListModel.addElement("Could not load history: " + ex.getMessage());
        }
    }

    /** Parses the amount field, showing an error message if it's invalid. Returns null on failure. */
    private Double parseAmount(String text, JLabel messageLabel) {
        try {
            double amount = Double.parseDouble(text.trim());
            if (amount <= 0) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Enter an amount greater than 0.");
                return null;
            }
            return amount;
        } catch (NumberFormatException ex) {
            messageLabel.setForeground(Color.RED);
            messageLabel.setText("Please enter a valid number.");
            return null;
        }
    }

    /** Updates the dashboard labels to reflect the currently logged-in user. */
    private void refreshDashboard() {
        if (currentUser == null) return;
        welcomeLabel.setText("Welcome, " + currentUser.name + "!");
        balanceLabel.setText("Balance: $" + String.format("%.2f", currentUser.balance));
    }

    // ------------------------------------------------------------------
    // MAIN - program entry point
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimpleBankUI_SQLite app = new SimpleBankUI_SQLite();
            app.setVisible(true);
        });
    }
}
