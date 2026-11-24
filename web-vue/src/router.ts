import { createRouter, createWebHistory } from "vue-router";
import { wallet } from "./session";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: () => import("./pages/Welcome.vue") },
    { path: "/setup", component: () => import("./pages/Setup.vue") },
    {
      path: "/app",
      component: () => import("./components/Shell.vue"),
      children: [
        { path: "", component: () => import("./pages/Assets.vue") },
        { path: "send", component: () => import("./pages/Send.vue") },
        { path: "activity", component: () => import("./pages/Activity.vue") },
        { path: "cards", component: () => import("./pages/Cards.vue") },
        { path: "cards/:id", component: () => import("./pages/CardDetail.vue") },
        { path: "console", component: () => import("./pages/Console.vue") },
        { path: "settlement", component: () => import("./pages/Settlement.vue") },
        { path: "backup", component: () => import("./pages/Backup.vue") },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

router.beforeEach((to) => {
  const unlocked = wallet.session !== null;
  if (to.path.startsWith("/app") && !unlocked) return "/";
  if ((to.path === "/" || to.path === "/setup") && unlocked) return "/app";
});
