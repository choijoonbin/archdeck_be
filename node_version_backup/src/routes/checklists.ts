import { Router, Request, Response } from 'express';
import { db } from '../db.js';

export const checklistsRouter = Router();

// GET /api/checklists
checklistsRouter.get('/', (req: Request, res: Response) => {
  const { category, priority } = req.query;
  let items = db.getChecklists();

  if (category && typeof category === 'string' && category !== 'ALL') {
    items = items.filter(c => c.category.toLowerCase() === category.toLowerCase());
  }

  if (priority && typeof priority === 'string' && priority !== 'ALL') {
    items = items.filter(c => c.priority.toUpperCase() === priority.toUpperCase());
  }

  res.json(items);
});
