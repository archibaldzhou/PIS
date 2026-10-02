import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import App from './App';
import './style.css';

const root = document.getElementById('root');
if (!root) throw new Error('Missing application root');
ReactDOM.createRoot(root).render(
  <React.StrictMode>
    <ConfigProvider button={{ autoInsertSpace: false }} theme={{ token: { colorPrimary: '#126b67', borderRadius: 12 } }}>
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
