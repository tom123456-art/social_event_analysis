package com.social.hotspot.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social.hotspot.config.AiProperties;
import com.social.hotspot.config.ProjectPaths;
import com.social.hotspot.exception.BusinessException;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

@Service
public class AiModelRegistry {
    private final AiProperties config;
    private final ObjectMapper json;
    private final Path directory;
    @org.springframework.beans.factory.annotation.Autowired
    /** 构造模型注册中心，使用默认用户目录加载已配置的模型。 */
    public AiModelRegistry(AiProperties config, ObjectMapper json) {
        this(config, json, ProjectPaths.root().resolve("runtime/config/ai"));
    }
    /** 构造模型注册中心，指定模型配置文件目录，加载内置与自定义模型。 */
    public AiModelRegistry(AiProperties config, ObjectMapper json, Path directory) {
        this.config = config; this.json = json; this.directory = directory;
    }
    /** 返回内置默认模型配置。 */
    public AiModelSettings builtin() {
        return new AiModelSettings("qwen", "通义千问", "阿里云百炼", config.getModel(), config.getRequestUrl(), config.getApiKey(), config.getTimeoutSeconds(), null);
    }
    /** 列出指定用户可见的全部模型，返回对外展示视图。 */
    public synchronized List<Map<String, Object>> list(long user) {
        List<Map<String, Object>> result = new ArrayList<>(); result.add(builtin().publicView());
        load().getOrDefault(Long.toString(user), List.of()).forEach(item -> result.add(item.publicView()));
        return result;
    }
    /** 按用户与模型标识解析具体模型配置，找不到时返回内置模型。 */
    public synchronized AiModelSettings resolve(long user, String id) {
        if (id == null || id.isBlank() || "qwen".equals(id)) return builtin();
        return load().getOrDefault(Long.toString(user), List.of()).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("模型不存在或无权使用，请重新选择模型。"));
    }
    /** 保存用户自定义模型配置，加密密钥后持久化到配置文件。 */
    public synchronized void save(long user, AiModelSettings model) {
        Map<String, List<AiModelSettings>> all = load();
        List<AiModelSettings> models = new ArrayList<>(all.getOrDefault(Long.toString(user), List.of()));
        if (models.size() >= 20) throw new BusinessException("最多添加二十个自定义模型。");
        if (models.stream().anyMatch(item -> item.name().equalsIgnoreCase(model.name())) || "通义千问".equalsIgnoreCase(model.name()))
            throw new BusinessException("模型显示名称已存在，请更换名称。");
        models.add(model); all.put(Long.toString(user), models);
        try {
            Files.createDirectories(directory);
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(all));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length); System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            Path temporary = Files.createTempFile(directory, "models-", ".tmp");
            try {
                Files.write(temporary, output);
                Files.move(temporary, directory.resolve("models.enc"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception ex) { throw new BusinessException("模型测试成功，但配置保存失败，请检查后端配置目录权限。"); }
    }
    /** 从配置文件加载全部用户模型，解密密钥，失败时回退为内置模型。 */
    private Map<String, List<AiModelSettings>> load() {
        Path file = directory.resolve("models.enc");
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length < 29) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
            return json.readValue(cipher.doFinal(Arrays.copyOfRange(data, 12, data.length)), new TypeReference<LinkedHashMap<String, List<AiModelSettings>>>() {});
        } catch (Exception ex) { throw new BusinessException("读取自定义模型配置失败，请检查配置文件和加密密钥。"); }
    }
    /** 基于配置密钥派生 AES 加密密钥。 */
    private SecretKeySpec key() throws Exception {
        Path file = directory.resolve("models.key");
        if (!Files.exists(file)) {
            if (Files.exists(directory.resolve("models.enc"))) throw new IllegalStateException("Missing key");
            Files.createDirectories(directory);
            byte[] generated = new byte[32]; new SecureRandom().nextBytes(generated);
            Files.write(file, generated, StandardOpenOption.CREATE_NEW);
        }
        byte[] value = Files.readAllBytes(file);
        if (value.length != 32) throw new IllegalStateException("Invalid key");
        return new SecretKeySpec(value, "AES");
    }
}
