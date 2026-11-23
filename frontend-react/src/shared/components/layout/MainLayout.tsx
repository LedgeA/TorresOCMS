import { Outlet } from "react-router-dom";
import Sidenav from "./Sidenav";
import { Toaster } from "@/shared/components/ui/sonner";

const MainLayout: React.FC = () => {
  return (
    <div className="flex h-screen bg-brand-gray-lightest">
      <Sidenav />
      <main className="flex-1 overflow-y-auto p-8">
        <Outlet />
      </main>
      <Toaster />
    </div>
  );
};

export default MainLayout;
