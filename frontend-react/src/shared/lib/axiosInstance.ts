import axios from 'axios';


export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem("authToken");
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    // If sending FormData, let the browser set Content-Type with boundary
    if (config.data instanceof FormData) {
      delete config.headers["Content-Type"];
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem("authToken");
      localStorage.removeItem("pwChangeRequired");
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }

    if (error.response?.status === 403) {
      const data = error.response.data;
      if (data && (typeof data === "string" ? data : data.error || "").includes("Password change required")) {
        localStorage.setItem("pwChangeRequired", "true");
        if (window.location.pathname !== "/enforce-password-change") {
          window.location.href = "/enforce-password-change";
        }
      }
    }

    const serverMessage = error.response?.data;
    if (serverMessage) {
      if (typeof serverMessage === "string") {
        error.message = serverMessage;
      } else if (typeof serverMessage === "object") {
        const messages = Object.values(serverMessage).flat().join(", ");
        if (messages) error.message = messages;
      }
    }

    return Promise.reject(error);
  }
);

export default api;
