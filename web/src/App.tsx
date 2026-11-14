import { Navigate, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { Activity } from "./pages/Activity";
import { Assets } from "./pages/Assets";
import { Backup } from "./pages/Backup";
import { CardDetail } from "./pages/CardDetail";
import { Cards } from "./pages/Cards";
import { Console } from "./pages/Console";
import { Send } from "./pages/Send";
import { Settlement } from "./pages/Settlement";
import { Setup } from "./pages/Setup";
import { Welcome } from "./pages/Welcome";
import { RequireSession } from "./session";

// 定义页面路由。钱包内的页面必须先解锁。
export function App() {
  return (
    <Routes>
      <Route path="/" element={<Welcome />} />
      <Route path="/setup" element={<Setup />} />
      <Route path="/app" element={<RequireSession><Shell /></RequireSession>}>
        <Route index element={<Assets />} />
        <Route path="send" element={<Send />} />
        <Route path="activity" element={<Activity />} />
        <Route path="cards" element={<Cards />} />
        <Route path="cards/:id" element={<CardDetail />} />
        <Route path="console" element={<Console />} />
        <Route path="settlement" element={<Settlement />} />
        <Route path="backup" element={<Backup />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
