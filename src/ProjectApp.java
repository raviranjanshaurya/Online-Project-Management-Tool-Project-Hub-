import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

public class ProjectApp extends HttpServlet {
  static final String URL="jdbc:mysql://localhost:3306/project_hub_pro_db?useSSL=false&allowPublicKeyRetrieval=true";
  static String dbUser(){return System.getenv().getOrDefault("PM_DB_USER","root");}
  static String dbPass(){return System.getenv().getOrDefault("PM_DB_PASS","");}
  static class DB implements AutoCloseable {
    Connection c;
    DB() throws SQLException { try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch(ClassNotFoundException ex) { throw new SQLException("MySQL Connector/J is missing from Tomcat WEB-INF/lib. Restart Tomcat after deploying the JAR.",ex); } c=DriverManager.getConnection(URL,dbUser(),dbPass()); }
    PreparedStatement sql(String q,Object... args) throws SQLException {PreparedStatement p=c.prepareStatement(q);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}
    List<Map<String,String>> list(String q,Object... args) throws SQLException {try(PreparedStatement p=sql(q,args);ResultSet r=p.executeQuery()){List<Map<String,String>> rows=new ArrayList<>();ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,String> x=new LinkedHashMap<>();for(int i=1;i<=m.getColumnCount();i++)x.put(m.getColumnLabel(i),r.getString(i));rows.add(x);}return rows;}}
    int run(String q,Object... args) throws SQLException {try(PreparedStatement p=sql(q,args)){return p.executeUpdate();}}
    public void close() throws SQLException {c.close();}
  }
  interface Dashboard {String title();}
  static abstract class Role implements Dashboard {final String name;Role(String n){name=n;}public String title(){return name+" Dashboard";}}
  static class Admin extends Role {Admin(){super("Admin");}}
  static class Manager extends Role {Manager(){super("Project Manager");}}
  static class Member extends Role {Member(){super("Team Member");}}
  static final List<String> activity=Collections.synchronizedList(new ArrayList<>());
  static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"activity-heartbeat");t.setDaemon(true);return t;});
  public void init(){worker.scheduleAtFixedRate(()->{synchronized(activity){if(activity.size()>40)activity.subList(0,activity.size()-40).clear();}},1,1,TimeUnit.MINUTES);}
  public void destroy(){worker.shutdownNow();}
  static String hash(String s){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder z=new StringBuilder();for(byte x:b)z.append(String.format("%02x",x));return z.toString();}catch(Exception e){throw new IllegalStateException(e);}}
  static String h(String s){if(s==null)return "";return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  static String v(HttpServletRequest r,String k){return Optional.ofNullable(r.getParameter(k)).orElse("").trim();}
  static int id(String s){try{return Integer.parseInt(s);}catch(Exception e){return -1;}}
  static String options(List<Map<String,String>> rows,String key,String label){StringBuilder b=new StringBuilder();for(var x:rows)b.append("<option value='").append(h(x.get(key))).append("'>").append(h(x.get(label))).append("</option>");return b.toString();}
  static void addActivityLog(String message){activity.add(message);}
  protected void doGet(HttpServletRequest req,HttpServletResponse res)throws IOException {page(req,res);}
  protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException {
    req.setCharacterEncoding("UTF-8");String action=v(req,"action");HttpSession session=req.getSession();String role=(String)session.getAttribute("role");int uid=id(String.valueOf(session.getAttribute("uid")));
    try(DB db=new DB()){
      if(action.equals("setup")){if(!db.list("SELECT id FROM users LIMIT 1").isEmpty())throw new Exception("Initial setup already completed");if(v(req,"password").length()<8)throw new Exception("Use at least 8 characters");db.run("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,'Admin')",v(req,"name"),v(req,"email"),hash(v(req,"password")));addActivityLog("Admin account created");}
      else if(action.equals("login")){var rows=db.list("SELECT id,name,role FROM users WHERE email=? AND password_hash=?",v(req,"email"),hash(v(req,"password")));if(rows.isEmpty())throw new Exception("Invalid login");var x=rows.get(0);req.getSession().invalidate();session=req.getSession(true);session.setAttribute("uid",x.get("id"));session.setAttribute("name",x.get("name"));session.setAttribute("role",x.get("role"));}
      else if(action.equals("logout")){session.invalidate();}
      else if(uid<1){throw new Exception("Please log in");}
      else if(action.equals("user")&&"Admin".equals(role)){String userRole=v(req,"role");if(!List.of("Admin","Manager","Member").contains(userRole))throw new Exception("Invalid role");if(v(req,"password").length()<8)throw new Exception("Password must be 8+ characters");db.run("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,?)",v(req,"name"),v(req,"email"),hash(v(req,"password")),userRole);addActivityLog("User added: "+v(req,"name"));}
      else if(action.equals("deleteUser")&&"Admin".equals(role)){int target=id(v(req,"id"));if(target==uid)throw new Exception("Cannot delete your own account");db.run("DELETE FROM users WHERE id=?",target);addActivityLog("User removed");}
      else if(action.equals("project")&&("Admin".equals(role)||"Manager".equals(role))){db.run("INSERT INTO projects(title,description,deadline,manager_id) VALUES(?,?,NULLIF(?,''),?)",v(req,"title"),v(req,"description"),v(req,"deadline"),uid);addActivityLog("Project created: "+v(req,"title"));}
      else if(action.equals("deleteProject")&&"Admin".equals(role)){db.run("DELETE FROM projects WHERE id=?",id(v(req,"id")));addActivityLog("Project removed");}
      else if(action.equals("task")&&("Admin".equals(role)||"Manager".equals(role))){int project=id(v(req,"project"));if("Manager".equals(role)&&db.list("SELECT id FROM projects WHERE id=? AND manager_id=?",project,uid).isEmpty())throw new Exception("Not your project");if(db.list("SELECT id FROM users WHERE id=? AND role='Member'",id(v(req,"assignee"))).isEmpty())throw new Exception("Choose a team member");db.run("INSERT INTO tasks(project_id,title,assignee_id) VALUES(?,?,?)",project,v(req,"title"),id(v(req,"assignee")));addActivityLog("Task assigned: "+v(req,"title"));}
      else if(action.equals("status")){String status=v(req,"status");if(!List.of("To Do","In Progress","Done").contains(status))throw new Exception("Invalid status");int task=id(v(req,"id"));int count;if("Member".equals(role))count=db.run("UPDATE tasks SET status=? WHERE id=? AND assignee_id=?",status,task,uid);else if("Manager".equals(role))count=db.run("UPDATE tasks t JOIN projects p ON p.id=t.project_id SET t.status=? WHERE t.id=? AND p.manager_id=?",status,task,uid);else if("Admin".equals(role))count=db.run("UPDATE tasks SET status=? WHERE id=?",status,task);else throw new Exception("Access denied");if(count==0)throw new Exception("Task not found or access denied");addActivityLog("Task status updated");}
      else if(action.equals("profile")){if(v(req,"name").isEmpty()||v(req,"email").isEmpty())throw new Exception("Name and email required");if(!v(req,"password").isEmpty()){if(v(req,"password").length()<8)throw new Exception("Password must be 8+ characters");db.run("UPDATE users SET name=?,email=?,password_hash=? WHERE id=?",v(req,"name"),v(req,"email"),hash(v(req,"password")),uid);}else db.run("UPDATE users SET name=?,email=? WHERE id=?",v(req,"name"),v(req,"email"),uid);session.setAttribute("name",v(req,"name"));addActivityLog("Profile updated");}
      else if(action.equals("setting")&&"Admin".equals(role)){db.run("INSERT INTO settings(setting_key,setting_value) VALUES('site_name',?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)",v(req,"site_name"));addActivityLog("Settings updated");}
      else throw new Exception("Action not permitted");
      res.sendRedirect(req.getContextPath()+"/?message="+URLEncoder.encode("Saved successfully",StandardCharsets.UTF_8));
    }catch(Exception e){res.sendRedirect(req.getContextPath()+"/?error="+URLEncoder.encode(e.getMessage()==null?"Database error":e.getMessage(),StandardCharsets.UTF_8));}
  }
  static String form(String action,String content){return "<form class='action-form' method='post'><input type='hidden' name='action' value='"+action+"'>"+content+"<button class='btn primary'>Save changes</button></form>";}
  static String field(String name,String type,String placeholder){return "<input name='"+name+"' type='"+type+"' placeholder='"+placeholder+"' "+(type.equals("password")?"":"")+" required>";}
  static String table(List<Map<String,String>> rows){if(rows.isEmpty())return "<p>No records yet.</p>";StringBuilder b=new StringBuilder("<div class='scroll'><table><tr>");for(String k:rows.get(0).keySet())b.append("<th>").append(h(k)).append("</th>");b.append("</tr>");for(var row:rows){b.append("<tr>");for(String x:row.values())b.append("<td>").append(h(x)).append("</td>");b.append("</tr>");}return b.append("</table></div>").toString();}
  static String kanban(List<Map<String,String>> tasks){
    StringBuilder b=new StringBuilder("<div class='kanban-board'>");
    String[] statuses={"To Do","In Progress","Done"};
    String[] classes={"todo","doing","done"};
    for(int i=0;i<statuses.length;i++){
      String status=statuses[i]; int count=0;
      for(var t:tasks)if(status.equals(t.get("status")))count++;
      b.append("<section class='kanban-column'><div class='kanban-heading'><span class='kanban-dot ").append(classes[i]).append("'></span><b>").append(status).append("</b><span class='kanban-count'>").append(count).append("</span></div>");
      for(var t:tasks)if(status.equals(t.get("status"))){
        b.append("<article class='kanban-card'><div class='kanban-card-top'><span class='kanban-tag'>TASK #").append(h(t.get("id"))).append("</span><span class='kanban-more'>•••</span></div><h4>").append(h(t.get("title"))).append("</h4><p>").append(h(t.get("project"))).append("</p><div class='kanban-card-bottom'><span class='mini-avatar'>").append(h(t.get("assignee")==null||t.get("assignee").isEmpty()?"?":t.get("assignee").substring(0,1))).append("</span><small>").append(h(t.get("assignee"))).append("</small><span class='kanban-priority ").append(classes[i]).append("'>").append(status).append("</span></div></article>");
      }
      if(count==0)b.append("<div class='kanban-empty'>No tasks in this stage</div>");
      b.append("</section>");
    }
    return b.append("</div>").toString();
  }
  static String tile(String icon,String number,String label,String trend){return "<article class='metric card'><div class='metric-top'><span class='metric-icon'>"+icon+"</span><span class='trend'>"+trend+"</span></div><strong>"+number+"</strong><small>"+label+"</small></article>";}
  static String panel(String title,String subtitle,String content){return "<section class='card panel'><div class='panel-heading'><div><h3>"+title+"</h3><p>"+subtitle+"</p></div><span class='dots'>•••</span></div>"+content+"</section>";}
  static String taskCards(List<Map<String,String>> tasks){StringBuilder b=new StringBuilder("<div class='task-list'>");for(var t:tasks){String status=t.get("status");String cl="Done".equals(status)?"done":"In Progress".equals(status)?"doing":"todo";b.append("<div class='task-item'><span class='task-icon'>✓</span><div class='task-info'><b>").append(h(t.get("title"))).append("</b><small>").append(h(t.get("project"))).append(" · ").append(h(t.get("assignee"))).append("</small></div><span class='status ").append(cl).append("'>").append(h(status)).append("</span></div>");}if(tasks.isEmpty())b.append("<p class='empty'>No tasks yet. Add your first task to get started.</p>");return b.append("</div>").toString();}
  void page(HttpServletRequest req,HttpServletResponse res)throws IOException {
    res.setContentType("text/html;charset=UTF-8");StringBuilder body=new StringBuilder();HttpSession s=req.getSession(false);String role=s==null?null:(String)s.getAttribute("role");int uid=s==null?-1:id(String.valueOf(s.getAttribute("uid")));
    String tab=v(req,"tab");if(tab.isEmpty())tab="overview";
    try(DB db=new DB()){
      if(role==null){
        boolean first=db.list("SELECT id FROM users LIMIT 1").isEmpty();
        body.append("<div class='auth-page'><div class='auth-glow'></div><div class='auth-card card'><div class='auth-logo'>✦</div><h1>Welcome to Project Hub</h1><p>One workspace. Every project. Your team's best work.</p><div class='auth-tag'>"+(first?"GET STARTED":"WELCOME BACK")+"</div><h2>"+(first?"Create administrator account":"Sign in to your workspace")+"</h2>");
        if(first)body.append(form("setup",field("name","text","Full name")+field("email","email","Work email")+field("password","password","Password (8+ characters)")));
        else body.append(form("login",field("email","email","Work email")+field("password","password","Password")));
        body.append("<div class='auth-foot'>Secure workspace · Java + MySQL</div></div></div>");
      }else{
        Dashboard dashboard="Admin".equals(role)?new Admin():"Manager".equals(role)?new Manager():new Member();
        String name=(String)s.getAttribute("name");
        var projects=db.list("SELECT p.id,p.title,p.description,p.deadline,p.status,u.name AS manager FROM projects p LEFT JOIN users u ON u.id=p.manager_id "+("Member".equals(role)?"WHERE p.id IN (SELECT project_id FROM tasks WHERE assignee_id=?)":"Manager".equals(role)?"WHERE p.manager_id=?":"")+" ORDER BY p.id DESC",("Admin".equals(role)?new Object[]{}:new Object[]{uid}));
        var tasks=db.list("SELECT t.id,t.title,p.title AS project,u.name AS assignee,t.status FROM tasks t JOIN projects p ON p.id=t.project_id LEFT JOIN users u ON u.id=t.assignee_id "+("Member".equals(role)?"WHERE t.assignee_id=?":"Manager".equals(role)?"WHERE p.manager_id=?":"")+" ORDER BY t.id DESC",("Admin".equals(role)?new Object[]{}:new Object[]{uid}));
        long done=tasks.stream().filter(t->"Done".equals(t.get("status"))).count();long progress=tasks.isEmpty()?0:done*100/tasks.size();
        String root=req.getContextPath()+"/";
        body.append("<div class='app-shell'><aside class='sidebar'><div class='brand'><span class='brand-mark'>✦</span><span>project<span class='brand-light'>hub</span><small>PRO WORKSPACE</small></span></div><div class='nav-caption'>WORKSPACE</div><nav>");
        String[][] nav={{"overview","◫","Overview"},{"projects","▦","Projects"},{"tasks","☑","My tasks"},{"team","♧","Team"},{"settings","⚙","Settings"}};
        for(String[] n:nav){if("team".equals(n[0])&&!"Admin".equals(role))continue;if("settings".equals(n[0])&&!"Admin".equals(role))continue;body.append("<a class='nav-link ").append(tab.equals(n[0])?"active":"").append("' href='").append(root).append("?tab=").append(n[0]).append("'><span>").append(n[1]).append("</span>").append(n[2]).append("</a>");}
        body.append("</nav><div class='sidebar-bottom'><div class='help-card'><span>✧</span><b>Stay in sync</b><p>Great things are built together.</p></div><div class='side-user'><span class='avatar'>").append(h(name.substring(0,1).toUpperCase())).append("</span><div><b>").append(h(name)).append("</b><small>").append(h(dashboard.title())).append("</small></div></div></div></aside><div class='workspace'><header class='topbar'><button class='menu-toggle' onclick='document.body.classList.toggle(&quot;show-menu&quot;)'>☰</button><div class='breadcrumbs'>Workspace <span>/</span> <b>").append(h(tab.substring(0,1).toUpperCase()+tab.substring(1))).append("</b></div><div class='top-actions'><span class='online-dot'></span><span class='online-label'>Workspace online</span><span class='avatar small'>").append(h(name.substring(0,1).toUpperCase())).append("</span></div></header><main class='content'>");
        body.append("<div class='welcome'><div><div class='eyebrow'>YOUR WORKSPACE <span>✦</span></div><h1>").append(tab.equals("overview")?"Good to see you, "+h(name.split(" ")[0])+" 👋":h(tab.substring(0,1).toUpperCase()+tab.substring(1))).append("</h1><p>").append(tab.equals("overview")?"Here's what's happening across your projects today.":"Everything you need, organized in one place.").append("</p></div><div class='date-pill'>◷ <span id='today'>Today</span></div></div>");
        if(tab.equals("overview")){
          body.append("<div class='metrics'>").append(tile("▦",String.valueOf(projects.size()),"Active projects","All projects")).append(tile("☑",String.valueOf(tasks.size()),"Total tasks","Assigned")).append(tile("◉",String.valueOf(done),"Completed tasks","Well done")).append(tile("↗",progress+"%","Completion rate","Live data")).append("</div>");
          body.append("<div class='two-col'><section class='card feature-card'><div class='panel-heading'><div><h3>Project progress</h3><p>Overall task completion</p></div><span class='chip'>Live</span></div><div class='progress-layout'><div class='donut' style='--pct:").append(progress).append("'><div><strong>").append(progress).append("%</strong><small>completed</small></div></div><div class='progress-legend'><div><span class='dot purple'></span>Completed <b>").append(done).append("</b></div><div><span class='dot orange'></span>Remaining <b>").append(tasks.size()-done).append("</b></div><p>Keep moving forward, one task at a time.</p></div></div></section>");
          body.append(panel("Recent tasks","A snapshot of your team's latest work",taskCards(tasks))).append("</div>");
          body.append(panel("Projects at a glance","Your current project portfolio",table(projects)));
        }else if(tab.equals("projects")){
          body.append(panel("Project portfolio","Track your projects, owners and deadlines",table(projects)));
          if(!"Member".equals(role))body.append(panel("Create a new project","Start something great",form("project",field("title","text","Project title")+"<textarea name='description' placeholder='What is this project about?'></textarea><label>Deadline</label><input name='deadline' type='date'>")));
          if("Admin".equals(role))body.append(panel("Delete a project","Enter the project ID to remove it",form("deleteProject",field("id","number","Project ID"))));
        }else if(tab.equals("tasks")){
          body.append(panel("Task board","Manage assignments and update status",kanban(tasks)));
          body.append(panel("All task details","View the complete task list",table(tasks)));
          body.append("<div class='card panel'><div class='panel-heading'><div><h3>Update task status</h3><p>Choose the task and its new state</p></div></div>");
          for(var t:tasks)body.append(form("status","<input type='hidden' name='id' value='"+h(t.get("id"))+"'><div class='inline-label'>"+h(t.get("title"))+"</div><select name='status'><option"+("To Do".equals(t.get("status"))?" selected":"")+">To Do</option><option"+("In Progress".equals(t.get("status"))?" selected":"")+">In Progress</option><option"+("Done".equals(t.get("status"))?" selected":"")+">Done</option></select>"));
          body.append("</div>");
          if(!"Member".equals(role)){var members=db.list("SELECT id,name FROM users WHERE role='Member' ORDER BY name");body.append(panel("Assign a task","Delegate work to a team member",form("task",field("title","text","Task title")+"<label>Project</label><select name='project'>"+options(projects,"id","title")+"</select><label>Assignee</label><select name='assignee'>"+options(members,"id","name")+"</select>")));}
        }else if(tab.equals("team")&&"Admin".equals(role)){
          body.append(panel("Team directory","Manage access and roles",table(db.list("SELECT id,name,email,role FROM users ORDER BY id"))));
          body.append(panel("Add team member","Create a new workspace account",form("user",field("name","text","Full name")+field("email","email","Email address")+field("password","password","Temporary password (8+ characters)")+"<select name='role'><option>Member</option><option>Manager</option><option>Admin</option></select>")));
          body.append(panel("Remove user","Cannot remove your own account",form("deleteUser",field("id","number","User ID"))));
        }else if(tab.equals("settings")&&"Admin".equals(role))body.append(panel("Workspace settings","Configure your workspace",form("setting",field("site_name","text","Workspace name"))));
        else body.append(panel("Workspace","Choose an option from the sidebar","<p>Open a section to get started.</p>"));
        var me=db.list("SELECT name,email FROM users WHERE id=?",uid);
        body.append("<div class='two-col lower'><section class='card panel'><div class='panel-heading'><div><h3>My profile</h3><p>Keep your account information current</p></div></div>");
        if(!me.isEmpty())body.append("<p class='muted'>Signed in as "+h(me.get(0).get("email"))+"</p>"+form("profile",field("name","text","New name")+field("email","email","New email")+"<input type='password' name='password' placeholder='New password (optional)'>"));
        body.append("</section><section class='card panel'><div class='panel-heading'><div><h3>Activity feed</h3><p>Recent workspace updates</p></div></div><div class='feed'>");
        synchronized(activity){for(int i=activity.size()-1;i>=Math.max(0,activity.size()-6);i--)body.append("<div class='feed-row'><span class='feed-icon'>✦</span><span>").append(h(activity.get(i))).append("</span></div>");}
        body.append("</div></section></div><footer>© Project Hub Pro · Built with Java, JDBC & Tomcat <form method='post'><input type='hidden' name='action' value='logout'><button class='logout'>Sign out →</button></form></footer></main></div></div>");
      }
    }catch(Exception e){body.append("<div class='error-page card'><span>⚠</span><h2>Connection error</h2><p>").append(h(e.getMessage())).append("</p><small>Check the MySQL Connector/J driver, database.sql, credentials, and restart Tomcat.</small></div>");}
    try(PrintWriter w=res.getWriter()){
      w.print("<!doctype html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Project Hub Pro</title><link rel='stylesheet' href='"+req.getContextPath()+"/style.css'></head><body>");
      if(req.getParameter("error")!=null)w.print("<div class='toast bad'>"+h(req.getParameter("error"))+"</div>");
      if(req.getParameter("message")!=null)w.print("<div class='toast good'>"+h(req.getParameter("message"))+"</div>");
      w.print(body+"<script src='"+req.getContextPath()+"/app.js'></script></body></html>");
    }
  }
}
