<script setup lang="ts">
import BrandMark from './BrandMark.vue'
</script>

<template>
  <main class="auth-page">
    <section class="auth-identity">
      <img class="auth-campus-art" src="/assets/zzu-campus-lineart.png" alt="" aria-hidden="true" />
      <div class="auth-data-orbit orbit-a" aria-hidden="true"></div>
      <div class="auth-data-orbit orbit-b" aria-hidden="true"></div>
      <div class="auth-brand">
        <BrandMark />
        <div>
          <p>HERCERT · 河南教育科研网</p>
          <h1>网络安全垂域智能体</h1>
        </div>
      </div>
      <div class="auth-intro">
        <span class="eyebrow"><i></i> 郑州大学 · 省网安全智能研判平台</span>
        <h2>让威胁研判更快一步，<br />让每一次分析都有迹可循。</h2>
        <p>汇聚多源威胁情报、漏洞数据与智能分析能力，为全省教育行业提供安全研判支持。</p>
        <div class="trust-list">
          <span>专属会话空间</span><span>多源情报联动</span><span>全过程审计</span>
        </div>
      </div>
      <p class="auth-copyright">河南省教育科研计算机网络中心</p>
    </section>
    <section class="auth-panel">
      <div class="auth-card"><slot /></div>
    </section>
  </main>
</template>
