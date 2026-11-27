import express, { Request, Response } from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { topicsRouter } from './routes/topics.js';
import { comparisonsRouter } from './routes/comparisons.js';
import { glossaryRouter } from './routes/glossary.js';
import { checklistsRouter } from './routes/checklists.js';
import { userRouter } from './routes/user.js';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 4000;

// Middlewares
app.use(cors({
  origin: ['http://localhost:5173', 'http://127.0.0.1:5173'],
  credentials: true
}));
app.use(express.json());

// Health Check
app.get('/api/health', (_req: Request, res: Response) => {
  res.json({
    status: 'ok',
    service: 'ArchDeck API Server',
    version: '1.0.0',
    timestamp: new Date().toISOString()
  });
});

// Mount Routes
app.use('/api/topics', topicsRouter);
app.use('/api/comparisons', comparisonsRouter);
app.use('/api/glossary', glossaryRouter);
app.use('/api/checklists', checklistsRouter);
app.use('/api/user', userRouter);

// 404 Handler
app.use((_req: Request, res: Response) => {
  res.status(404).json({ error: '요청하신 API 엔드포인트가 존재하지 않습니다.' });
});

// Start Server
app.listen(PORT, () => {
  console.log(`🚀 [ArchDeck Backend] Server running on http://localhost:${PORT}`);
  console.log(`📋 Health Check: http://localhost:${PORT}/api/health`);
});
