/* METADATA
{
  "name": "nanobanana_Integration",
  "description": "使用 Nano Banana API (基于Grsai的api服务/https://grsai.com/) 根据提示词画图，支持文生图和图生图（可传入参考图片URL进行图像编辑与合成），将图片保存到本地 /sdcard/Download/Operit/draws/ 目录，并返回 Markdown 图片提示,集成 Nano Banana 绘图, ImgLoc 图床(https://imgloc.com/)与file_converter文件压缩。绘图时若提供本地参考图，会自动通过file_converter压缩到10mb以内并通过 ImgLoc 上传获取 URL。（注意！！！使用本工具时请不要使用代理工具，否则有可能导致生图失败）",
  "env": [
    "NANOBANANA_API_KEY",
    "IMGLOC_API_KEY"
  ],
  "tools": [
    {
      "name": "draw_image",
      "description": "Nano Banana AI绘图。支持文生图、图生图（大文件会自动压缩后上传）。",
      "parameters": [
        { "name": "prompt", "description": "绘图提示词", "type": "string", "required": true },
        { "name": "model", "description": "模型名称，默认 nano-banana-pro", "type": "string", "required": false },
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
    // ==========================================
    // 0. 通用配置与依赖
    // ==========================================
    const client = OkHttp.newClient();
    
    // Nano Banana 配置
    const NANO_API_ENDPOINT = "https://grsai.dakka.com.cn/v1/draw/nano-banana";
    const NANO_RESULT_ENDPOINT = "https://grsai.dakka.com.cn/v1/draw/result";
    const DEFAULT_MODEL = "nano-banana-pro";
    
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

    // ==========================================
    // 1. 系统底层工具 (终端/依赖)
    // ==========================================
    
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

    // ==========================================
    // 2. 压缩与上传逻辑 (核心修改)
    // ==========================================

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
        try
