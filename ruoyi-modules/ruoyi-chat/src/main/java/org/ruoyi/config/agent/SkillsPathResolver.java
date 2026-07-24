package org.ruoyi.config.agent;

import java.nio.file.Path;

/**
 * 磁盘 Skills 目录路径解析器
 * <p>
 * langchain4j 的 ShellSkills 通过 FileSystemSkillLoader 从磁盘加载 SKILL.md，
 * 路径硬编码在 ChatServiceFacade 中。抽到此工具类供智能体管理端与聊天流程共用，
 * 避免两处路径漂移。
 *
 * @author ruoyi team
 */
public final class SkillsPathResolver {

    private SkillsPathResolver() {
    }

    /**
     * 返回磁盘 skills 目录的绝对路径，按场景顺位检测：
     * 1. {user.dir}/src/main/resources/skills（当在 ruoyi-admin 子模块或 IDEA 中运行）
     * 2. {user.dir}/ruoyi-admin/src/main/resources/skills（当在项目根目录运行）
     */
    public static Path resolveSkillsPath() {
        String userDir = System.getProperty("user.dir");
        
        Path directPath = Path.of(userDir, "src/main/resources/skills");
        if (java.nio.file.Files.exists(directPath)) {
            return directPath;
        }

        Path subModulePath = Path.of(userDir, "ruoyi-admin", "src", "main", "resources", "skills");
        if (java.nio.file.Files.exists(subModulePath)) {
            return subModulePath;
        }

        try {
            java.nio.file.Files.createDirectories(directPath);
        } catch (Exception ignored) {
        }
        return directPath;
    }

}
