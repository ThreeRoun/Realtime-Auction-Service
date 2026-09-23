import { BrowserRouter, Routes, Route } from "react-router-dom";
import Navbar from "./components/Navbar";
import ProductList from "./pages/ProductList";
import ProductDetail from "./pages/ProductDetail";
import Login from "./pages/Login";
import ProductCreate from "./pages/ProductCreate";
import MyPage from "./pages/MyPage";
import AuctionResult from "./pages/AuctionResult";
import "./App.css";

function App() {
  return (
    <BrowserRouter>
      <Navbar />

      <Routes>
        <Route path="/" element={<ProductList />} />
        <Route path="/products/:id" element={<ProductDetail />} />
        <Route path="/login" element={<Login />} />
        <Route path="/products/new" element={<ProductCreate />} />
        <Route path="/mypage" element={<MyPage />} />
        <Route path="/auction/result" element={<AuctionResult />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;