import { Navigate, Route, Routes } from 'react-router-dom'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<main><h1>平台总管理端登录</h1></main>} />
      <Route path="/" element={<main><h1>平台工作台</h1></main>} />
      <Route path="/not-found" element={<main><h1>页面不存在</h1></main>} />
      <Route path="*" element={<Navigate to="/not-found" replace />} />
    </Routes>
  )
}
