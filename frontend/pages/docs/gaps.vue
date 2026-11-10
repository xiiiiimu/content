<script setup lang="ts">
useHead({ title: '能力缺口 · Content' })
</script>

<template>
  <MainContent back="/hot-topics">
    <template #title>
      <MainTitle>后端能力缺口</MainTitle>
    </template>
    <div class="px4 py4 text-sm leading-relaxed space-y-3">
      <p>以下能力 Content 后端当前未提供或未完整暴露；前端已隐藏或降级，不伪造数据。</p>
      <ul class="list-disc ps5 space-y-2">
        <li>用户登录 / 注册 HTTP</li>
        <li>创建草稿（发布仅支持已有 DRAFT）</li>
        <li>评论列表 / 线程读取</li>
        <li>「我的喜欢」列表</li>
        <li>话题帖服务端游标分页（limit≤50）</li>
        <li>用户搜索 / 作者资料目录</li>
        <li>Feed 实时 streaming</li>
        <li>媒体封面专用 HTTP sign</li>
        <li>热点参与者名单 / 头像 / 显示名</li>
      </ul>
      <p class="text-secondary">详见仓库 <code>frontend/docs/CAPABILITY-GAPS.md</code>。</p>
    </div>
  </MainContent>
</template>
