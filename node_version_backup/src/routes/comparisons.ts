import { Router, Request, Response } from 'express';
import { db } from '../db.js';

export const comparisonsRouter = Router();

// GET /api/comparisons
comparisonsRouter.get('/', (_req: Request, res: Response) => {
  const comparisons = db.getComparisons();
  res.json(comparisons);
});

// GET /api/comparisons/:id
comparisonsRouter.get('/:id', (req: Request, res: Response) => {
  const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;
  const comparison = db.getComparisonById(id);
  if (!comparison) {
    return res.status(404).json({ error: '비교 매트릭스를 찾을 수 없습니다.' });
  }
  res.json(comparison);
});
