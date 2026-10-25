/** 靜態版（Artifact）：沒有本機 server，不能執行程式、不能 AI 評分、不同步 */
export const IS_STATIC = import.meta.env.VITE_STATIC === '1'
