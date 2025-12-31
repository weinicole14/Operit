/* METADATA
{
  "name": "nanobanana_Integration",
  "description": "使用 Nano Banana API (支持自定义API地址) 根据提示词画图，支持文生图和图生图（可传入参考图片URL进行图像编辑与合成），将图片保存到本地 /sdcard/Download/Operit/draws/ 目录，并返回 Markdown 图片提示,集成 Nano Banana 绘图, ImgLoc 图床(https://imgloc.com/)与file_converter文件压缩。绘图时若提供本地参考图，会自动通过file_converter压缩到10mb以内并通过 ImgLoc 上传获取 URL。（注意！！！使用本工具时请不要使用代理工具，否则有可能导致生图失败）",
  "env": [
    "NANOBANANA_API_URL",
    "NANOBANANA_API_KEY",
    "NANOBANANA_MODEL",
    "IMGLOC_API_KEY"
  ],
  "tools": [
    {
      "name": "draw_image",
      "description": "Nano Banana AI绘图。支持文生图、图生图（大文件会自动压缩后上传）。",
      "parameters": [
        { "name": "prompt", "description": "绘图提示词", "type": "string", "required": true },
        { "name": "model", "description": "模型名称，若不填则使用环境变量配置的默认模型", "type": "string", "required": false },
        { "name": "aspect_ratio", "description": "比例 (如 1:1, 16:9)", "type": "string", "required": false },
        { "name": "image_size", "description": "分辨率 (如 1K, 4K)", "type": "string", "required": false },
        { "name": "image_urls", "description": "网络参考图 URL 数组", "type": "array", "required": false },
        { "name": "image_paths", "description": "本地参考图路径数组 (会自动压缩并上传)", "type": "array", "required": false },
        { "name": "file_name", "description": "保存文件名", "type": "string", "required": false }
      ]
    },
    {
      "name": "upload_image",
      "description": "单独使用：将本地图片压缩(如需)并上传到 ImgLoc 图床。",
      "parameters": [
        { "name": "file_path", "description": "图片绝对路径", "type": "string", "required": true }
      ]
    }
  ]
}
*/

const integratedTool = (function () {
    // 0. 通用配置与依赖
    const client = OkHttp.newClient();
    
    // Nano Banana 配置
    // 修改：优先读取环境变量 NANOBANANA_MODEL，若未配置则默认为 nano-banana-pro
    const DEFAULT_MODEL = getEnv("NANOBANANA_MODEL") ? getEnv("NANOBANANA_MODEL").trim() : "nano-banana-pro";
    
    // 文件保存配置
    const DOWNLOAD_ROOT = "/sdcard/Download";
    const OPERIT_DIR = `${DOWNLOAD_ROOT}/Operit`;
    const DRAWS_DIR = `${OPERIT_DIR}/draws`;
    const TEMP_DIR = `${OPERIT_DIR}/temp_compress`; // 临时文件夹

    // 轮询配置 (10分钟)
    const POLL_INTERVAL = 5000; 
    const MAX_WAIT_TIME = 600000; 

    // ImgLoc 配置
    const IMGLOC_API_ENDPOINT = "https://imgloc.com/api/1/upload";
    
    // 终端会话缓存
    let terminalSessionId = null;

    // 1. 系统底层工具 (终端/依赖)
    
    async function getTerminalSessionId() {
        if (terminalSessionId) {
            return terminalSessionId;
        }
        const session = await Tools.System.terminal.create("integrated_session");
        terminalSessionId = session.sessionId;
        return terminalSessionId;
    }

    async function executeTerminalCommand(command) {
        const sessionId = await getTerminalSessionId();
        return await Tools.System.terminal.exec(sessionId, command);
    }

    // 统一的依赖检查安装函数 (融合自 file_converter)
    async function checkAndInstall(toolName, packageName) {
        console.log(`[System] 检查依赖: ${toolName}...`);
        const checkCmd = `command -v ${toolName}`;
        const checkResult = await executeTerminalCommand(checkCmd);
        
        if (checkResult.exitCode === 0 && checkResult.output.trim() !== '') {
            return true;
        }
        
        console.log(`[System] 未找到 ${toolName}，尝试安装包: ${packageName}...`);
        const installCmd = `apt-get update && apt-get install -y ${packageName}`;
        const installResult = await executeTerminalCommand(installCmd);
        
        if (installResult.exitCode !== 0) {
            throw new Error(`依赖安装失败: ${toolName} (包名: ${packageName})。输出: ${installResult.output}`);
        }
        console.log(`[System] ${packageName} 安装成功。`);
        return true;
    }

    // 获取文件大小 (字节)
    async function getFileSize(filePath) {
        // 使用 wc -c 读取字节数，适用于大多数 Linux 环境
        const cmd = `wc -c < "${filePath}"`;
        const result = await executeTerminalCommand(cmd);
        if (result.exitCode !== 0) {
            // 兜底：如果 wc 失败，尝试 ls -l
            const lsCmd = `ls -l "${filePath}" | awk '{print $5}'`;
            const lsResult = await executeTerminalCommand(lsCmd);
            if (lsResult.exitCode === 0) return parseInt(lsResult.output.trim(), 10);
            throw new Error(`无法获取文件大小: ${filePath}`);
        }
        return parseInt(result.output.trim(), 10);
    }

    // 2. 压缩与上传逻辑 (核心修改)

    function getImgLocApiKey() {
        const key = getEnv("IMGLOC_API_KEY");
        if (!key) throw new Error("IMGLOC_API_KEY 未设置。");
        return key;
    }

    // 智能压缩逻辑
    async function smartCompressIfNeeded(inputPath) {
        const LIMIT_BYTES = 10 * 1024 * 1024; // 10MB
        
        // 1. 获取大小
        let size = 0;
        try {
            size = await getFileSize(inputPath);
        } catch (e) {
            console.warn(`[Compress] 获取文件大小失败，跳过压缩检查: ${e.message}`);
            return { path: inputPath, isTemp: false };
        }

        console.log(`[Compress] 文件大小: ${(size / 1024 / 1024).toFixed(2)} MB`);

        // 2. 如果小于 10MB，直接返回
        if (size <= LIMIT_BYTES) {
            return { path: inputPath, isTemp: false };
        }

        // 3. 需要压缩
        console.log(`[Compress] 文件超过 10MB，正在进行智能压缩...`);
        await checkAndInstall("convert", "imagemagick"); // 确保 ImageMagick 已安装
        await Tools.Files.mkdir(TEMP_DIR);

        const tempFileName = `compressed_${Date.now()}.jpg`; // 强制转为 JPG 以便于控制体积
        const tempPath = `${TEMP_DIR}/${tempFileName}`;

        // 使用 ImageMagick 的 extent 参数尝试控制在 10MB (10000KB) 以内
        // -define jpeg:extent=10MB 会自动调整质量参数以适应大小
        const cmd = `convert "${inputPath}" -define jpeg:extent=9500kb "${tempPath}"`; 
        
        const result = await executeTerminalCommand(cmd);
        if (result.exitCode !== 0) {
            throw new Error(`图片压缩失败: ${result.output}`);
        }

        // 验证压缩结果
        const newSize = await getFileSize(tempPath);
        console.log(`[Compress] 压缩完成。新大小: ${(newSize / 1024 / 1024).toFixed(2)} MB`);

        return { path: tempPath, isTemp: true };
    }

    // 上传流程 (整合压缩)
    async function internal_upload_to_imgloc(filePath) {
        // 清理路径字符串
        const cleanPath = filePath.replace(/['"]/g, '').trim();

        // 1. 检查原文件
        const fileExists = await Tools.Files.exists(cleanPath);
        if (!fileExists.exists) throw new Error(`文件未找到: ${cleanPath}`);

        // 2. 智能压缩 (如果不超标则返回原路径)
        const fileObj = await smartCompressIfNeeded(cleanPath);
        const pathToSend = fileObj.path;

        try {
            // 3. 准备上传环境
            await checkAndInstall("curl", "curl");
            const apiKey = getImgLocApiKey();
            
            // 4. 构建 Curl 命令
            let command = `curl -s -L --fail -A "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"`;
            command += ` -H "X-API-Key: ${apiKey}"`;
            command += ` -X POST -F "source=@${pathToSend}"`; // 使用处理后的路径
            command += ` "${IMGLOC_API_ENDPOINT}"`;

            console.log(`[ImgLoc] 正在上传: ${pathToSend} ...`);
            
            // 5. 执行上传
            const result = await executeTerminalCommand(command);
            
            if (result.exitCode !== 0) {
                throw new Error(`上传请求失败 (Exit Code ${result.exitCode})。\n终端输出: ${result.output}`);
            }

            const responseText = result.output.trim();
            if (!responseText) throw new Error("上传失败：服务器返回空内容。");

            let jsonResponse;
            try {
                const jsonStart = responseText.indexOf('{');
                const jsonEnd = responseText.lastIndexOf('}');
                if (jsonStart !== -1 && jsonEnd !== -1) {
                    jsonResponse = JSON.parse(responseText.substring(jsonStart, jsonEnd + 1));
                } else {
                    jsonResponse = JSON.parse(responseText);
                }
            } catch (e) {
                throw new Error(`API 响应解析失败: ${responseText.substring(0, 200)}...`);
            }

            if (jsonResponse.status_code === 200 && jsonResponse.image) {
                return {
                    url: jsonResponse.image.url,
                    details: jsonResponse.image
                };
            } else {
                const errMsg = jsonResponse.error ? jsonResponse.error.message : (jsonResponse.status_txt || "未知错误");
                throw new Error(`ImgLoc API 报错: ${errMsg}`);
            }

        } finally {
            // 6. 清理临时文件 (如果是生成的压缩文件)
            if (fileObj.isTemp) {
                console.log(`[System] 清理临时文件: ${fileObj.path}`);
                const rmCmd = `rm "${fileObj.path}"`;
                await executeTerminalCommand(rmCmd);
            }
        }
    }

    // 3. Nano Banana 绘图逻辑

    function parseInputArray(input) {
        if (!input) return [];
        if (Array.isArray(input)) return input;
        if (typeof input === 'string') {
            const trimmed = input.trim();
            if ((trimmed.startsWith('[') && trimmed.endsWith(']'))) {
                try {
                    const parsed = JSON.parse(trimmed);
                    if (Array.isArray(parsed)) return parsed;
                } catch (e) {}
            }
            return trimmed.split(',').map(s => s.trim()).filter(s => s.length > 0);
        }
        return [];
    }

    // 获取 Nano Banana API Key
    function getNanoApiKey() {
        const apiKey = getEnv("NANOBANANA_API_KEY");
        if (!apiKey) throw new Error("NANOBANANA_API_KEY 未配置。");
        return apiKey;
    }

    // 新增：获取 Nano Banana Base URL 并格式化
    function getNanoBaseUrl() {
        let url = getEnv("NANOBANANA_API_URL");
        if (!url) throw new Error("NANOBANANA_API_URL 未配置。请填写 API 基础地址 (例如 https://grsai.dakka.com.cn)");
        // 移除末尾的斜杠，防止路径拼接时出现 // 
        return url.replace(/\/$/, "");
    }

    function sanitizeFileName(name) {
        const safe = name.replace(/[\\/:*?"<>|]/g, "_").trim();
        return safe ? safe.substring(0, 80) : `nano_draw_${Date.now()}`;
    }

    function buildFileName(prompt, customName) {
        if (customName && customName.trim().length > 0) return sanitizeFileName(customName);
        const shortPrompt = prompt.length > 40 ? `${prompt.substring(0, 40)}...` : prompt;
        return `${sanitizeFileName(shortPrompt || "image")}_${Date.now()}`;
    }

    async function ensureDirectories() {
        const dirs = [DOWNLOAD_ROOT, OPERIT_DIR, DRAWS_DIR];
        for (const dir of dirs) {
            try { await Tools.Files.mkdir(dir); } catch (e) { /* ignore */ }
        }
    }

    async function callNanobananaApi(params) {
        const apiKey = getNanoApiKey();
        const baseUrl = getNanoBaseUrl();
        // 动态构建绘图 Endpoint
        const drawEndpoint = `${baseUrl}/v1/draw/nano-banana`;

        // 优先使用传入参数的 model，否则使用环境配置的 DEFAULT_MODEL
        const model = (params.model && params.model.trim()) ? params.model.trim() : DEFAULT_MODEL;

        const body = {
            model: model,
            prompt: params.prompt,
            webHook: "-1",
            shutProgress: false
        };

        if (params.aspect_ratio) body.aspectRatio = params.aspect_ratio.trim();
        if (params.image_size) body.imageSize = params.image_size.trim();
        if (params.image_urls && Array.isArray(params.image_urls)) {
            body.urls = params.image_urls.filter(url => url && url.trim().length > 0);
        }

        const request = client.newRequest()
            .url(drawEndpoint)
            .method("POST")
            .headers({
                "accept": "application/json",
                "content-type": "application/json",
                "Authorization": `Bearer ${apiKey}`
            })
            .body(JSON.stringify(body), "json");

        console.log(`步骤1/2: 提交绘图任务到 ${drawEndpoint}...`);
        console.log(`使用模型: ${model}`);
        const response = await request.build().execute();
        
        if (!response.isSuccessful()) {
            throw new Error(`Nano Banana API 提交失败: ${response.statusCode} - ${response.content}`);
        }

        let parsed;
        try { parsed = JSON.parse(response.content); } catch (e) { throw new Error("API响应解析失败"); }

        if (!parsed || !parsed.data || !parsed.data.id) {
            throw new Error("API响应中未找到任务ID: " + JSON.stringify(parsed));
        }
        return parsed.data.id;
    }

    async function pollForResult(taskId) {
        const apiKey = getNanoApiKey();
        const baseUrl = getNanoBaseUrl();
        // 动态构建结果查询 Endpoint
        const resultEndpoint = `${baseUrl}/v1/draw/result`;

        const startTime = Date.now();
        let attempts = 0;

        console.log(`步骤2/2: 等待任务完成 (最大等待: ${MAX_WAIT_TIME/1000/60}分钟)...`);

        while (Date.now() - startTime < MAX_WAIT_TIME) {
            attempts++;
            const request = client.newRequest()
                .url(resultEndpoint)
                .method("POST")
                .headers({
                    "accept": "application/json",
                    "content-type": "application/json",
                    "Authorization": `Bearer ${apiKey}`
                })
                .body(JSON.stringify({ id: taskId }), "json");

            const response = await request.build().execute();
            
            if (response.isSuccessful()) {
                let parsed;
                try { parsed = JSON.parse(response.content); } catch (e) {}

                if (parsed && parsed.code === 0 && parsed.data) {
                    const status = parsed.data.status || "unknown";
                    const progress = parsed.data.progress || 0;

                    if (attempts % 2 === 0) console.log(`[轮询] 状态: ${status} | 进度: ${progress}%`);

                    if (status === "succeeded") {
                        if (parsed.data.results && parsed.data.results[0] && parsed.data.results[0].url) {
                            return String(parsed.data.results[0].url);
                        }
                        throw new Error("任务显示成功但未返回图片URL");
                    } else if (status === "failed") {
                        throw new Error(`任务失败: ${parsed.data.failure_reason || "未知原因"}`);
                    }
                }
            }
            await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL));
        }
        throw new Error("任务超时");
    }

    // 4. 对外工具 Wrapper

    async function upload_image_tool(params) {
        try {
            const result = await internal_upload_to_imgloc(params.file_path);
            complete({ success: true, message: "图片上传成功", data: result });
        } catch (error) {
            console.error(`Error: ${error.message}`);
            complete({ success: false, message: error.message, error_stack: error.stack });
        }
    }

    async function draw_image_tool(params) {
        try {
            if (!params.prompt) throw new Error("提示词 prompt 不能为空。");

            let imageUrlsArray = parseInputArray(params.image_urls);
            let imagePathsArray = parseInputArray(params.image_paths);

            // 处理本地图片上传
            if (imagePathsArray.length > 0) {
                console.log(`检测到 ${imagePathsArray.length} 张本地参考图，准备处理...`);
                for (let p of imagePathsArray) {
                    if (!p) continue;
                    // internal_upload_to_imgloc 内部包含 压缩 -> 上传 逻辑
                    const uploadResult = await internal_upload_to_imgloc(p);
                    imageUrlsArray.push(uploadResult.url);
                    console.log(`  -> 上传成功: ${uploadResult.url}`);
                }
            }

            await ensureDirectories();

            const taskId = await callNanobananaApi({
                ...params,
                image_urls: imageUrlsArray
            });

            const imageUrl = await pollForResult(taskId);

            const ext = imageUrl.match(/\.(png|jpg|jpeg|webp|gif)/i) ? RegExp.$1.toLowerCase() : "png";
            const baseName = buildFileName(params.prompt, params.file_name);
            const savePath = `${DRAWS_DIR}/${baseName}.${ext}`;

            const downloadRes = await Tools.Files.download(imageUrl, savePath);
            if (!downloadRes.successful) throw new Error("下载生成的图片失败");

            const markdown = `![AI生成图片](file://${savePath})`;
            
            complete({
                success: true,
                message: "图片生成完成",
                data: {
                    file_path: savePath,
                    markdown: markdown,
                    source_url: imageUrl,
                    hint: `图片已保存至: ${savePath}\n请直接输出此 Markdown:\n\n${markdown}`
                }
            });

        } catch (error) {
            console.error("绘图失败:", error);
            complete({
                success: false,
                message: `执行失败: ${error.message}`,
                error_stack: error.stack
            });
        }
    }

    return {
        upload_image: upload_image_tool,
        draw_image: draw_image_tool
    };
})();

exports.upload_image = integratedTool.upload_image;
exports.draw_image = integratedTool.draw_image;
